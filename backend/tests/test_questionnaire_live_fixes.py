"""Custom questionnaire fixes from the 21 Oct 2026 audit (AUDIT_DR_2026-10-21.md), against a database.

* **M1** an admin's re-upload that drops a question with only BLANK answer rows deletes it (it used to
  500 on the RESTRICT foreign key and leave the edit half applied);
* **M2** a designer's re-upload never removes or retires a question left out of the file; it is kept
  and reported, because removing questionnaire items is a content admin's on every path (owner,
  21 Oct 2026); and taking a section out through PATCH is refused the same way;
* **M3** answers typed into a re-uploaded workbook are counted and reported, not dropped in silence;
* **M5** one retired question, or one answer somebody else wrote, is rejected on its own and the rest
  of the batch is saved;
* **M6** an empty answers batch is refused, and a PUT returns only the caller's own rows when the
  caller may not read every sitting;
* **M17** a notes-only answer counts as answered, so its question is retired and not deleted.

Needs a LOCAL Postgres (``checks.yml``'s on-demand ``backend-db`` job provides one); skips otherwise.
"""

import os
import uuid
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token
from app.services.questionnaire_xlsx import build_questionnaire_workbook

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in (
            ("owner", "DESIGNER", "Form Owner"),
            ("colleague", "DESIGNER", "Colleague Designer"),
            ("stranger", "DESIGNER", "Stranger Designer"),
            ("admin", "ADMIN", "Content Admin"),
        ):
            people[slug] = await db.user.create(
                data={"email": f"qlive-{slug}-{stamp}@example.org", "name": name, "role": role}
            )
    finally:
        await db.disconnect()
    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp}


def _as(env: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


def _form(env: dict[str, Any], prompts: list[str]) -> dict[str, Any]:
    made = env["client"].post(
        "/api/questionnaires",
        json={
            "title": f"Live fixes {uuid.uuid4().hex[:6]} {env['stamp']}",
            "sections": [
                {"code": "A", "title": "About the loom", "questions": [{"prompt": p} for p in prompts]}
            ],
        },
        headers=_as(env, "owner"),
    )
    assert made.status_code == 201, made.text
    return made.json()


def _ids(form: dict[str, Any]) -> dict[str, str]:
    return {q["prompt"]: q["id"] for s in form["sections"] for q in s["questions"]}


def _sitting(env: dict[str, Any], form: dict[str, Any], slug: str = "owner") -> str:
    made = env["client"].post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Kamala Devi"},
        headers=_as(env, slug),
    )
    assert made.status_code == 201, made.text
    return made.json()["id"]


def _put(env, form, entry_id, answers, slug="owner"):
    return env["client"].put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={"answers": answers},
        headers=_as(env, slug),
    )


def _reupload(env, form, keep: list[tuple[str, str]], slug: str, answers=None, labels=None):
    """Upload a workbook naming only ``keep`` ([(question id, prompt)]) over ``form``."""
    workbook = build_questionnaire_workbook(
        title=form["title"],
        description=None,
        questionnaire_id=form["id"],
        version=form.get("version", 1),
        sections=[
            {
                "code": "A",
                "title": "About the loom",
                "questions": [
                    {"id": qid, "prompt": prompt, "answers": (answers or {}).get(qid, {})}
                    for qid, prompt in keep
                ],
            }
        ],
        entry_labels=labels,
    )
    return env["client"].post(
        f"/api/questionnaires/{form['id']}/upload",
        files={"file": ("edit.xlsx", workbook, XLSX_MIME)},
        headers=_as(env, slug),
    )


def _read(env, form, slug="owner") -> dict[str, Any]:
    got = env["client"].get(
        f"/api/questionnaires/{form['id']}", params={"includeRetired": True}, headers=_as(env, slug)
    )
    assert got.status_code == 200, got.text
    return {q["id"]: q for s in got.json()["sections"] for q in s["questions"]}


# ── M2: one removal rule, re-upload included ─────────────────────────────────────────────────


def test_a_designers_re_upload_keeps_the_questions_it_leaves_out(env):
    form = _form(env, ["How many looms?", "Who taught you?", "Which dyes?"])
    ids = _ids(form)
    response = _reupload(env, form, [(ids["How many looms?"], "How many looms?")], slug="owner")
    assert response.status_code == 200, response.text
    report = response.json()["report"]
    assert report["kept"] == 2
    assert report["removed"] == 0 and report["retired"] == 0
    assert sum("Only an admin can remove questions" in p["reason"] for p in report["problems"]) == 2
    stored = _read(env, form)
    assert all(stored[qid]["isActive"] for qid in ids.values())


def test_a_designer_cannot_take_a_section_out_but_an_admin_can(env):
    form = _form(env, ["How many looms?"])
    section_id = form["sections"][0]["id"]
    path = f"/api/questionnaires/{form['id']}/sections/{section_id}"
    refused = env["client"].patch(path, json={"isActive": False}, headers=_as(env, "owner"))
    assert refused.status_code == 403, refused.text
    allowed = env["client"].patch(path, json={"isActive": False}, headers=_as(env, "admin"))
    assert allowed.status_code == 200, allowed.text
    back = env["client"].patch(path, json={"isActive": True}, headers=_as(env, "owner"))
    assert back.status_code == 200, back.text


# ── M1: a blank answer row no longer turns an admin's re-upload into a 500 ──────────────────


def test_an_admins_re_upload_deletes_a_question_whose_only_answer_rows_are_blank(env):
    form = _form(env, ["How many looms?", "Tabbed past"])
    ids = _ids(form)
    entry_id = _sitting(env, form)
    saved = _put(env, form, entry_id, [{"questionId": ids["Tabbed past"], "answerText": ""}])
    assert saved.status_code == 200, saved.text
    response = _reupload(env, form, [(ids["How many looms?"], "How many looms?")], slug="admin")
    assert response.status_code == 200, response.text
    assert response.json()["report"]["removed"] == 1
    assert ids["Tabbed past"] not in _read(env, form)


# ── M17: notes are an answer ─────────────────────────────────────────────────────────────────


def test_a_notes_only_answer_retires_its_question_on_every_removal_path(env):
    form = _form(env, ["How many looms?", "Declined on re-upload", "Declined on delete"])
    ids = _ids(form)
    entry_id = _sitting(env, form)
    saved = _put(
        env,
        form,
        entry_id,
        [
            {"questionId": ids["Declined on re-upload"], "notes": "Declined to say"},
            {"questionId": ids["Declined on delete"], "notes": "Ask the son"},
        ],
    )
    assert saved.status_code == 200, saved.text

    removed = env["client"].delete(
        f"/api/questionnaires/{form['id']}/questions/{ids['Declined on delete']}",
        headers=_as(env, "admin"),
    )
    assert removed.status_code == 200, removed.text
    assert removed.json()["action"] == "retired"

    response = _reupload(env, form, [(ids["How many looms?"], "How many looms?")], slug="admin")
    assert response.status_code == 200, response.text
    assert response.json()["report"]["retired"] == 1
    stored = _read(env, form)
    assert stored[ids["Declined on re-upload"]]["isActive"] is False
    assert stored[ids["Declined on delete"]]["isActive"] is False


# ── M3: answers in a re-uploaded file are reported ───────────────────────────────────────────


def test_answers_typed_into_a_re_uploaded_file_are_reported_not_dropped_silently(env):
    form = _form(env, ["How many looms?"])
    ids = _ids(form)
    qid = ids["How many looms?"]
    response = _reupload(
        env, form, [(qid, "How many looms?")], slug="owner", answers={qid: {"R": "Twelve"}},
        labels=["R"],
    )
    assert response.status_code == 200, response.text
    report = response.json()["report"]
    assert report["answersNotImported"] == 1
    assert any("not imported" in p["reason"] for p in report["problems"])


# ── M5: per-item rejections ──────────────────────────────────────────────────────────────────


def test_a_retired_question_is_rejected_alone_and_the_rest_of_the_batch_is_saved(env):
    form = _form(env, ["Old wording", "Still asked"])
    ids = _ids(form)
    entry_id = _sitting(env, form)
    assert _put(env, form, entry_id, [{"questionId": ids["Old wording"], "answerText": "4"}]).status_code == 200
    retired = env["client"].delete(
        f"/api/questionnaires/{form['id']}/questions/{ids['Old wording']}", headers=_as(env, "admin")
    )
    assert retired.json()["action"] == "retired"
    second = _sitting(env, form)
    response = _put(
        env,
        form,
        second,
        [
            {"questionId": ids["Old wording"], "answerText": "5"},
            {"questionId": ids["Still asked"], "answerText": "Yes"},
        ],
    )
    assert response.status_code == 200, response.text
    saved = response.json()["saved"]
    assert saved["created"] == 1
    assert [(r["questionId"], r["code"]) for r in saved["rejected"]] == [
        (ids["Old wording"], "QUESTION_RETIRED")
    ]


def test_somebody_elses_answer_is_rejected_alone_and_an_identical_resend_is_fine(env):
    form = _form(env, ["How many looms?", "Who taught you?"])
    ids = _ids(form)
    entry_id = _sitting(env, form)
    first = _put(env, form, entry_id, [{"questionId": ids["How many looms?"], "answerText": "12"}])
    assert first.status_code == 200, first.text

    same = _put(
        env,
        form,
        entry_id,
        [
            {"questionId": ids["How many looms?"], "answerText": "12"},
            {"questionId": ids["Who taught you?"], "answerText": "My mother"},
        ],
        slug="colleague",
    )
    assert same.status_code == 200, same.text
    assert same.json()["saved"]["rejected"] == []
    assert same.json()["saved"]["created"] == 1

    changed = _put(
        env,
        form,
        entry_id,
        [{"questionId": ids["How many looms?"], "answerText": "Thirteen"}],
        slug="colleague",
    )
    assert changed.status_code == 200, changed.text
    rejected = changed.json()["saved"]["rejected"]
    assert [(r["questionId"], r["code"]) for r in rejected] == [
        (ids["How many looms?"], "ANSWER_OWNED_BY_OTHER")
    ]


# ── M6: the answers PUT is not a read of somebody else's sitting ─────────────────────────────


def test_an_empty_batch_is_refused_and_a_stranger_gets_only_their_own_rows_back(env):
    form = _form(env, ["How many looms?", "Who taught you?"])
    ids = _ids(form)
    entry_id = _sitting(env, form)
    assert _put(env, form, entry_id, [{"questionId": ids["How many looms?"], "answerText": "12"}]).status_code == 200

    empty = _put(env, form, entry_id, [], slug="stranger")
    assert empty.status_code == 422, empty.text

    mine = _put(
        env, form, entry_id, [{"questionId": ids["Who taught you?"], "answerText": "Uncle"}],
        slug="stranger",
    )
    assert mine.status_code == 200, mine.text
    returned = mine.json()["answers"]
    assert [a["questionId"] for a in returned] == [ids["Who taught you?"]]

    owner = _put(env, form, entry_id, [{"questionId": ids["How many looms?"], "answerText": "12"}])
    assert {a["questionId"] for a in owner.json()["answers"]} == set(ids.values())
