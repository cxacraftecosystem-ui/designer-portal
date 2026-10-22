"""The readers' view of the shared questionnaire (UNIFY_2026-10-21.md C10).

Tasks, record completeness, the stage counts (``stage_computed``, ``stage_assist``), the export tree
and the report annexure used to read the retired global form's responses and the forms' sittings
(``QuestionnaireFormEntry``). With ``QUESTIONNAIRE_V2`` on, the same facts live in sessions, takes
(``questionnaire_take_v``, live sessions only) and answers (``QuestionnaireAnswer`` and its current
revision), and each reader adds them through the helpers here. A soft-deleted session or form is
invisible to every one of them (the live filter, owner decision 3).

While the switch is off every helper answers empty WITHOUT A QUERY, so the readers behave and cost
exactly as they did before.
"""

from __future__ import annotations

from typing import Any


def enabled() -> bool:
    from app.core.config import get_settings

    return bool(get_settings().questionnaire_v2)


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


async def forms_for_design_workshop(design_workshop_id: str, *, kind: str | None = None, client: Any = None) -> list[dict[str, Any]]:
    """Every live form bound to a design workshop (bindings, which mirror the old attachment)."""
    if not enabled():
        return []
    params: list[Any] = [design_workshop_id]
    kind_sql = ""
    if kind:
        params.append(kind)
        kind_sql = ' AND q."kind" = $2'
    rows = await _db(client).query_raw(
        'SELECT DISTINCT q."id", q."title", q."kind", q."createdAt", b."isPrimary" FROM "QuestionnaireBinding" b '
        'JOIN "Questionnaire" q ON q."id" = b."questionnaireId" '
        'WHERE b."designWorkshopId" = $1 AND q."isActive" AND q."deletedAt" IS NULL' + kind_sql + ' ORDER BY q."createdAt"',
        *params,
    )
    return [dict(r) for r in rows]


async def session_counts(form_ids: list[str], *, design_workshop_id: str | None = None, client: Any = None) -> dict[str, dict[str, Any]]:
    """``{formId: {"count", "latest"}}``: live sessions on each form (a sitting is a session now)."""
    ids = sorted({str(f) for f in form_ids if f})
    if not enabled() or not ids:
        return {}
    params: list[Any] = [ids]
    scope = ""
    if design_workshop_id:
        params.append(design_workshop_id)
        scope = ' AND i."designWorkshopId" = $2'
    rows = await _db(client).query_raw(
        'SELECT i."questionnaireId" AS q, count(*) AS n, max(i."sessionDate") AS latest FROM "QuestionnaireInterview" i '
        'WHERE i."questionnaireId" = ANY($1::text[]) AND i."deletedAt" IS NULL AND i."mergedIntoId" IS NULL '
        'AND i."clientKey" IS NOT NULL' + scope + ' GROUP BY 1',
        *params,
    )
    return {str(r["q"]): {"count": int(r["n"]), "latest": r.get("latest")} for r in rows}


async def answer_counts(session_ids: list[str], *, client: Any = None) -> dict[str, int]:
    """``{sessionId: answers with text whose current revision was written in that session}``."""
    ids = sorted({str(s) for s in session_ids if s})
    if not enabled() or not ids:
        return {}
    rows = await _db(client).query_raw(
        'SELECT rv."sessionId" AS s, count(*) AS n FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" AND i."deletedAt" IS NULL '
        "WHERE rv.\"sessionId\" = ANY($1::text[]) AND btrim(rv.\"text\") <> '' GROUP BY 1",
        ids,
    )
    return {str(r["s"]): int(r["n"]) for r in rows}


async def covered_pairs(assignee_id: str, section_ids: list[str], *, workshop_id: str | None = None,
                        client: Any = None) -> set[tuple[str, str]]:
    """``{(subjectKey, sectionId)}`` this person covered: their takes (``recordedById``) and their
    typed answers with text (``actorId``). Typed or recorded, either counts (owner, amendment 2)."""
    ids = sorted({str(s) for s in section_ids if s})
    if not enabled() or not ids:
        return set()
    db = _db(client)
    params: list[Any] = [assignee_id, ids]
    scope = ""
    if workshop_id:
        params.append(workshop_id)
        scope = ' AND t."workshopId" = $3'
    takes = await db.query_raw(
        'SELECT DISTINCT t."subjectKey" AS k, t."sectionId" AS s FROM questionnaire_take_v t '
        'WHERE t."recordedById" = $1 AND t."sectionId" = ANY($2::text[])' + scope,
        *params,
    )
    scope = ' AND i."workshopId" = $3' if workshop_id else ""
    typed = await db.query_raw(
        'SELECT DISTINCT COALESCE(\'A:\' || a."artisanId", \'R:\' || a."respondentId") AS k, q."sectionId" AS s '
        'FROM "QuestionnaireAnswer" a JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" AND i."deletedAt" IS NULL '
        'JOIN "QuestionnaireQuestion" q ON q."lineageId" = a."questionLineageId" AND q."supersededById" IS NULL '
        "WHERE rv.\"actorId\" = $1 AND q.\"sectionId\" = ANY($2::text[]) AND btrim(rv.\"text\") <> ''" + scope,
        *params,
    )
    return {(str(r["k"]), str(r["s"])) for r in [*takes, *typed] if r.get("k") and r.get("s")}


async def session_answers(session_ids: list[str], *, client: Any = None) -> dict[str, list[dict[str, Any]]]:
    """``{sessionId: [{"displayCode", "prompt", "text", "owner"}]}``: the answers written in each live
    session, in the form's order, for the export tree and the annexure."""
    ids = sorted({str(s) for s in session_ids if s})
    if not enabled() or not ids:
        return {}
    rows = await _db(client).query_raw(
        'SELECT rv."sessionId" AS s, q."displayCode", q."prompt", q."sectionCode", rv."text", rv."note", '
        'COALESCE(ar."name", r."name") AS owner FROM "QuestionnaireAnswer" a '
        'JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" AND i."deletedAt" IS NULL '
        'JOIN "QuestionnaireQuestion" q ON q."lineageId" = a."questionLineageId" AND q."questionnaireId" = a."questionnaireId" '
        '  AND q."supersededById" IS NULL '
        'LEFT JOIN "Artisan" ar ON ar."id" = a."artisanId" LEFT JOIN "QuestionnaireRespondent" r ON r."id" = a."respondentId" '
        "WHERE rv.\"sessionId\" = ANY($1::text[]) AND btrim(rv.\"text\") <> '' "
        'ORDER BY rv."sessionId", q."sortOrder", q."id"',
        ids,
    )
    out: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        out.setdefault(str(r["s"]), []).append({
            "displayCode": r.get("displayCode"), "sectionCode": r.get("sectionCode"), "prompt": r.get("prompt") or "",
            "text": r.get("text") or "", "note": r.get("note"), "owner": r.get("owner"),
        })
    return out


async def annexure_sittings(form_ids: list[str], *, design_workshop_id: str, client: Any = None) -> dict[str, dict[str, Any]]:
    """``{formId: {"questionCount", "sittings"}}`` for the report annexure (A12): one sitting per
    respondent (artisan or guest) of each live session on the form filed under this design workshop,
    answered against the form's questions in the shared tables, in the form's order."""
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
