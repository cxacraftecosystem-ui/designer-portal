"""The 21 Oct 2026 questionnaire audit fixes (AUDIT_DR_2026-10-21.md), pinned without a database.

The behaviour is proved end to end in ``test_interview_live_fixes`` and ``test_questionnaire_live_fixes``
(database-backed); these drive the same functions with recording fakes, or read their source, so the
ordinary ``Checks`` run (which has no database) still fails when one of the fixes is undone.
"""

import asyncio
import inspect
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

BACKEND = Path(__file__).resolve().parents[1]


class _Delegate:
    def __init__(self, rows: list[Any] | None = None):
        self.rows = rows or []
        self.created: list[list[dict[str, Any]]] = []
        self.updated: list[tuple[Any, Any]] = []
        self.deleted: list[Any] = []
        self.deleted_many: list[Any] = []

    async def find_many(self, **_kwargs):
        return self.rows

    async def create_many(self, data):
        self.created.append(list(data))

    async def update(self, where, data):
        self.updated.append((where, data))

    async def delete(self, where):
        self.deleted.append(where)

    async def delete_many(self, where):
        self.deleted_many.append(where)


def _researcher(user_id: str = "usr_2") -> Any:
    return SimpleNamespace(id=user_id, name="Second Researcher", role="RESEARCHER")


def _row(**columns: Any) -> Any:
    return SimpleNamespace(**{"notes": None, "answerOrigin": None, **columns})


# ── H4: unchanged before authorship, and every conflict named ───────────────────────────────


def _interview_db(monkeypatch, stored: list[Any]) -> _Delegate:
    from app.api.routes import questionnaire

    responses = _Delegate(rows=stored)
    monkeypatch.setattr(
        questionnaire,
        "db",
        SimpleNamespace(
            questionnaireresponse=responses,
            questionnairequestion=_Delegate(rows=[SimpleNamespace(id=r.questionId) for r in stored]),
            questionnaireinterview=_Delegate(),
        ),
    )
    return responses


def test_resending_somebody_elses_answer_unchanged_is_not_refused(monkeypatch):
    from app.api.routes import questionnaire

    stored = [_row(id="r1", questionId="q1", answerText="Twelve", answeredById="usr_1")]
    responses = _interview_db(monkeypatch, stored)
    body = [SimpleNamespace(questionId="q1", answerText="Twelve", notes=None)]
    asyncio.run(questionnaire.upsert_responses("int_1", body, _researcher()))
    assert responses.updated == [] and responses.created == []


def test_changing_somebody_elses_answers_names_every_one_and_writes_nothing(monkeypatch):
    from app.api.routes import questionnaire

    stored = [
        _row(id="r1", questionId="q1", answerText="Twelve", answeredById="usr_1"),
        _row(id="r2", questionId="q2", answerText="Mother", answeredById="usr_1"),
    ]
    responses = _interview_db(monkeypatch, stored)
    body = [
        SimpleNamespace(questionId="q1", answerText="Thirteen", notes=None),
        SimpleNamespace(questionId="q2", answerText="Father", notes=None),
    ]
    with pytest.raises(HTTPException) as refused:
        asyncio.run(questionnaire.upsert_responses("int_1", body, _researcher()))
    assert refused.value.status_code == 403
    detail = refused.value.detail
    assert detail["code"] == "ANSWER_OWNED_BY_OTHER"
    assert detail["questionIds"] == ["q1", "q2"]
    assert detail["message"]
    assert responses.updated == [] and responses.created == []


def test_the_fold_writes_its_fill_and_its_answers_in_one_transaction():
    from app.api.routes import questionnaire

    source = inspect.getsource(questionnaire.merge_into_interview)
    code = source.split('"""')[-1]
    assert "async with db.tx() as tx:" in code
    assert "await tx.questionnaireinterview.update(" in code
    assert "client=tx" in code


# ── H5: the delete is soft, and the readers filter it ───────────────────────────────────────


def test_deleting_an_interview_is_a_soft_delete_that_frees_its_set():
    from app.api.routes import questionnaire

    code = inspect.getsource(questionnaire.delete_interview).split('"""')[-1]
    assert "questionnaireinterview.delete(" not in code
    assert '"deletedAt": datetime.now(UTC)' in code
    assert '"artisanSetKey": None' in code
    assert "assert_can_delete(current_user)" in code
    restore = inspect.getsource(questionnaire.restore_interview)
    assert "require_content_admin" in restore and "INTERVIEW_SET_TAKEN" in restore


@pytest.mark.parametrize(
    ("path", "needle"),
    [
        ("app/api/routes/questionnaire.py", '"deletedAt": {"not": None} if deletedOnly else None'),
        ("app/api/routes/questionnaire.py", 'where={**interview_where, "deletedAt": None}'),
        ("app/api/routes/answer_drafts.py", '"deletedAt": None'),
        ("app/api/routes/artisans.py", '"deletedAt": None'),
        ("app/api/routes/dashboard.py", '{"AND": [pending(view_where), {"deletedAt": None}]}'),
        ("app/api/routes/export.py", '{"AND": [rec_where, {"deletedAt": None}]}'),
        ("app/api/routes/tasks.py", 'where["deletedAt"] = None'),
        ("app/api/routes/data_browser.py", "_live(scope.records)"),
        ("app/services/questionnaire_consolidation.py", '"deletedAt": None'),
        ("app/services/workshop_inference.py", '"workshopId": None, "deletedAt": None'),
        ("app/services/search_index.py", "row is None or row.deletedAt is not None"),
        ("app/services/artisan_merge.py", '"deletedAt": None'),
    ],
)
def test_every_interview_reader_filters_deleted_rows(path, needle):
    assert needle in (BACKEND / path).read_text(encoding="utf-8")


def test_require_live_record_refuses_a_soft_deleted_row():
    from app.services.records import require_live_record

    class _One:
        def __init__(self, row):
            self.row = row

        async def find_unique(self, where):
            return self.row

    alive = SimpleNamespace(id="i1", deletedAt=None)
    assert asyncio.run(require_live_record(_One(alive), "i1")) is alive
    with pytest.raises(HTTPException) as gone:
        asyncio.run(require_live_record(_One(SimpleNamespace(id="i1", deletedAt="2026-10-21")), "i1"))
    assert gone.value.status_code == 404


# ── M5: per item ─────────────────────────────────────────────────────────────────────────────


def _forms_db(monkeypatch, stored: list[Any], questions: list[Any]) -> _Delegate:
    from app.services import questionnaire_forms

    answers = _Delegate(rows=stored)
    monkeypatch.setattr(
        questionnaire_forms,
        "db",
        SimpleNamespace(
            questionnaireformanswer=answers,
            questionnaireformquestion=_Delegate(rows=questions),
        ),
    )
    return answers


def _question(qid: str, active: bool = True) -> Any:
    return SimpleNamespace(
        id=qid, prompt=f"Prompt {qid}", isActive=active, section=SimpleNamespace(questionnaireId="qn")
    )


def test_a_retired_question_and_somebody_elses_answer_are_rejected_alone(monkeypatch):
    from app.services import questionnaire_forms

    answers = _forms_db(
        monkeypatch,
        stored=[_row(id="a2", questionId="q2", answerText="Twelve", answeredById="usr_1")],
        questions=[_question("q1", active=False), _question("q2"), _question("q3")],
    )
    entry = SimpleNamespace(id="ent", questionnaireId="qn")
    body = [
        SimpleNamespace(questionId="q1", answerText="new", notes=None),
        SimpleNamespace(questionId="q2", answerText="Thirteen", notes=None),
        SimpleNamespace(questionId="q3", answerText="Yes", notes=None),
    ]
    outcome = asyncio.run(questionnaire_forms.save_answers(entry, body, user_id="usr_2"))
    assert outcome["created"] == 1 and outcome["updated"] == 0
    assert [(r["questionId"], r["code"]) for r in outcome["rejected"]] == [
        ("q1", "QUESTION_RETIRED"),
        ("q2", "ANSWER_OWNED_BY_OTHER"),
    ]
    assert [row["questionId"] for row in answers.created[0]] == ["q3"]


def test_an_identical_resend_of_somebody_elses_answer_is_unchanged_not_rejected(monkeypatch):
    from app.services import questionnaire_forms

    _forms_db(
        monkeypatch,
        stored=[_row(id="a2", questionId="q2", answerText="Twelve", answeredById="usr_1")],
        questions=[_question("q2")],
    )
    entry = SimpleNamespace(id="ent", questionnaireId="qn")
    body = [SimpleNamespace(questionId="q2", answerText="Twelve", notes=None)]
    outcome = asyncio.run(questionnaire_forms.save_answers(entry, body, user_id="usr_2"))
    assert outcome == {"created": 0, "updated": 0, "unchanged": 1, "rejected": []}


def test_the_answers_put_refuses_an_empty_batch_and_narrows_what_it_returns():
    from app.api.routes import questionnaire_forms

    code = inspect.getsource(questionnaire_forms.record_answers).split('"""')[-1]
    assert "if not payload.answers:" in code
    assert 'where["answeredById"] = current_user.id' in code
    assert "_sees_every_sitting(" in code


# ── M17, M1, M2: what "answered" means, and who may remove ──────────────────────────────────


def test_a_notes_only_answer_counts_as_answered(monkeypatch):
    from app.services import questionnaire_forms

    _forms_db(
        monkeypatch,
        stored=[
            _row(questionId="q1", answerText=None, notes="Declined to say"),
            _row(questionId="q2", answerText="  ", notes=" "),
            _row(questionId="q3", answerText="Twelve"),
        ],
        questions=[],
    )
    answered = asyncio.run(questionnaire_forms._answered_question_ids(["q1", "q2", "q3"]))
    assert answered == {"q1", "q3"}


def _report() -> dict[str, Any]:
    return {"retired": 0, "removed": 0, "kept": 0, "problems": [], "details": []}


def test_without_may_remove_absent_questions_are_kept_and_reported():
    from app.services import questionnaire_forms

    client = SimpleNamespace(
        questionnaireformquestion=_Delegate(),
        questionnaireformsection=_Delegate(),
        questionnaireformanswer=_Delegate(),
    )
    questions = [
        SimpleNamespace(id="q1", prompt="Kept one", isActive=True, sectionId="s1"),
        SimpleNamespace(id="q2", prompt="Kept two", isActive=True, sectionId="s1"),
    ]
    sections = [SimpleNamespace(id="s1", isActive=True)]
    report = _report()
    bumps = asyncio.run(
        questionnaire_forms._remove_absent(
            questions, sections, set(), set(), set(), report, client=client, may_remove=False
        )
    )
    assert bumps == 0 and report["kept"] == 2 and report["removed"] == 0
    assert len(report["problems"]) == 2
    assert client.questionnaireformquestion.deleted == []
    assert client.questionnaireformsection.deleted == []


def test_with_may_remove_blank_answer_rows_go_before_the_question():
    from app.services import questionnaire_forms

    order: list[str] = []

    class _Recording(_Delegate):
        def __init__(self, name: str):
            super().__init__()
            self.name = name

        async def delete(self, where):
            order.append(f"{self.name}.delete")

        async def delete_many(self, where):
            order.append(f"{self.name}.delete_many")

    client = SimpleNamespace(
        questionnaireformquestion=_Recording("question"),
        questionnaireformsection=_Recording("section"),
        questionnaireformanswer=_Recording("answer"),
    )
    questions = [SimpleNamespace(id="q1", prompt="Tabbed past", isActive=True, sectionId="s1")]
    report = _report()
    asyncio.run(
        questionnaire_forms._remove_absent(
            questions, [], set(), set(), set(), report, client=client, may_remove=True
        )
    )
    assert order == ["answer.delete_many", "question.delete"]
    assert report["removed"] == 1


def test_the_re_upload_passes_the_removal_rule_and_runs_in_one_transaction():
    from app.api.routes import questionnaire_forms as routes
    from app.services import questionnaire_forms

    route = inspect.getsource(routes.reupload_questionnaire)
    assert "may_remove=is_content_admin(current_user)" in route
    edit = inspect.getsource(questionnaire_forms.apply_parsed_edit)
    assert "async with db.tx(" in edit
    assert "await db.questionnaireformquestion.update(" not in edit
    assert "answersNotImported" in edit


def test_taking_a_section_out_is_a_removal():
    from app.api.routes import questionnaire_forms

    code = inspect.getsource(questionnaire_forms.update_section)
    assert 'if data.get("isActive") is False and section.isActive:' in code
    assert "assert_can_delete(current_user)" in code


# ── M4: atomic creates ──────────────────────────────────────────────────────────────────────


def test_a_workbook_upload_and_a_new_section_are_each_one_transaction():
    from app.api.routes import questionnaire_forms as routes
    from app.services import questionnaire_forms

    create = inspect.getsource(questionnaire_forms.create_from_parsed).split('"""')[-1]
    assert "async with db.tx(" in create
    assert "questionnaireformsection.create_many(" in create
    assert "questionnaireformquestion.create_many(" in create
    assert "await db.questionnaireformquestion.create(" not in create
    assert "client=tx" in create
    section = inspect.getsource(routes.create_section)
    assert "async with db.tx(" in section
    assert "questionnaireformquestion.create_many(" in section
