import os
import uuid
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]


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
            ("lead", "DESIGNER", "Reuse Lead Designer"),
            ("outsider", "DESIGNER", "Unrelated Reuse Designer"),
            ("admin", "ADMIN", "Reuse Admin"),
        ):
            people[slug] = await db.user.create(
                data={
                    "email": f"qreuse-{slug}-{stamp}@example.org",
                    "name": name,
                    "role": role,
                }
            )
        for slug in ("lead", "outsider"):
            await db.designerroster.create(
                data={
                    "email": f"qreuse-{slug}-{stamp}@example.org",
                    "fullName": people[slug].name,
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp}


@pytest.fixture
def client(env):
    return env["client"]


def _as(env: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


INSTRUMENT = [
    {
        "code": "A",
        "title": "About the loom",
        "questions": [
            {"prompt": "How many looms do you own?", "helpText": "Working looms only.", "isRequired": True},
            {"prompt": "How old is the oldest one?"},
        ],
    },
    {
        "code": "B",
        "title": "About the yarn",
        "questions": [{"prompt": "Where is the yarn bought?", "isRequired": True}],
    },
]


def _workshop(client, env, title: str, slug: str = "lead") -> str:
    response = client.post(
        "/api/design-workshops", json={"title": title}, headers=_as(env, "admin")
    )
    assert response.status_code == 201, response.text
    workshop_id = response.json()["id"]
    granted = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [env["people"][slug].id]},
        headers=_as(env, "admin"),
    )
    assert granted.status_code == 200, granted.text
    return workshop_id


def _questionnaire(
    client,
    env,
    title: str,
    *,
    workshop_id: str | None = None,
    slug: str = "lead",
    description: str | None = None,
    sections: list[dict[str, Any]] | None = None,
) -> dict:
    body: dict[str, Any] = {
        "title": title,
        "sections": INSTRUMENT if sections is None else sections,
    }
    if workshop_id is not None:
        body["designWorkshopId"] = workshop_id
    if description is not None:
        body["description"] = description
    response = client.post("/api/questionnaires", json=body, headers=_as(env, slug))
    assert response.status_code == 201, response.text
    return response.json()


def _titles_owned_by(client, env, slug: str = "lead") -> list[str]:
    response = client.get(
        "/api/questionnaires?mineOnly=true&activeOnly=false&pageSize=100", headers=_as(env, slug)
    )
    assert response.status_code == 200, response.text
    return [row["title"] for row in response.json()["items"]]


def _prompts(form: dict) -> list[tuple[str, str]]:
    return [
        (section["code"], question["prompt"])
        for section in form["sections"]
        for question in section["questions"]
    ]


def _answered_sitting(client, env, form: dict, *, respondent: str, slug: str = "lead") -> str:
    entry = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": respondent},
        headers=_as(env, slug),
    )
    assert entry.status_code == 201, entry.text
    entry_id = entry.json()["id"]
    saved = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={
            "answers": [
                {"questionId": form["sections"][0]["questions"][0]["id"], "answerText": "Twelve"}
            ]
        },
        headers=_as(env, slug),
    )
    assert saved.status_code == 200, saved.text
    return entry_id


def _read(client, env, questionnaire_id: str, slug: str = "lead", *, retired: bool = False) -> dict:
    query = "?includeRetired=true" if retired else ""
    response = client.get(f"/api/questionnaires/{questionnaire_id}{query}", headers=_as(env, slug))
    assert response.status_code == 200, response.text
    return response.json()



def test_the_copy_carries_every_section_and_question_in_its_own_section(client, env):
    source_workshop = _workshop(client, env, f"Source workshop {env['stamp']}")
    target_workshop = _workshop(client, env, f"Target workshop {env['stamp']}")
    source = _questionnaire(
        client, env, f"Loom survey {env['stamp']}", workshop_id=source_workshop
    )

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": target_workshop, "title": f"Loom survey round two {env['stamp']}"},
        headers=_as(env, "lead"),
    )
    assert response.status_code == 201, response.text
    payload = response.json()
    copy = payload["questionnaire"]

    assert copy["id"] != source["id"]
    assert copy["designWorkshopId"] == target_workshop
    assert copy["title"] == f"Loom survey round two {env['stamp']}"
    assert copy["version"] == 1
    assert copy["sourceFilename"] is None

    assert _prompts(copy) == _prompts(source), "the instrument did not come across intact"
    assert copy["questionCount"] == source["questionCount"] == 3

    first = copy["sections"][0]["questions"][0]
    assert first["helpText"] == "Working looms only."
    assert first["isRequired"] is True
    assert first["supersededById"] is None
    assert first["retiredAt"] is None

    source_ids = {q["id"] for _, q in [(s, q) for s in source["sections"] for q in s["questions"]]}
    copy_ids = {q["id"] for s in copy["sections"] for q in s["questions"]}
    assert not (source_ids & copy_ids), "the copy is sharing question rows with the source"

    report = payload["report"]
    assert report["created"] == 3
    assert report["sections"] == 2
    assert report["problems"] == []
    assert report["provenance"]["action"] == "reused"
    assert report["provenance"]["sourceQuestionnaireId"] == source["id"]
    assert report["provenance"]["answersSkipped"] == 0
    assert payload["sourceQuestionnaireId"] == source["id"]


def test_retired_questions_do_not_come_across(client, env):
    source = _questionnaire(client, env, f"Retire source {env['stamp']}")
    doomed = source["sections"][1]["questions"][0]["id"]
    entry = client.post(
        f"/api/questionnaires/{source['id']}/entries",
        json={"respondentName": "Retirement respondent"},
        headers=_as(env, "lead"),
    )
    assert entry.status_code == 201, entry.text
    saved = client.put(
        f"/api/questionnaires/{source['id']}/entries/{entry.json()['id']}/answers",
        json={"answers": [{"questionId": doomed, "answerText": "The town market"}]},
        headers=_as(env, "lead"),
    )
    assert saved.status_code == 200, saved.text
    removed = client.delete(
        f"/api/questionnaires/{source['id']}/questions/{doomed}", headers=_as(env, "admin")
    )
    assert removed.status_code == 200, removed.text
    assert removed.json()["action"] == "retired", "the fixture did not retire anything"

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "lead")
    )
    assert response.status_code == 201, response.text
    copy = response.json()["questionnaire"]

    assert "Where is the yarn bought?" not in [p for _, p in _prompts(copy)]
    assert response.json()["report"]["created"] == 2

    still = _read(client, env, source["id"], retired=True)
    assert doomed in {q["id"] for s in still["sections"] for q in s["questions"]}
    assert any(a["answerText"] == "The town market" for e in still["entries"] for a in e["answers"])



def test_the_copy_arrives_with_no_sitting_and_no_answer(client, env):
    source_workshop = _workshop(client, env, f"Fieldwork source {env['stamp']}")
    target_workshop = _workshop(client, env, f"Fieldwork target {env['stamp']}")
    source = _questionnaire(
        client, env, f"Answered survey {env['stamp']}", workshop_id=source_workshop
    )
    respondent = f"Ramesh {env['stamp']}"
    _answered_sitting(client, env, source, respondent=respondent)

    before = _read(client, env, source["id"])
    assert len(before["entries"]) == 1
    assert before["entries"][0]["respondentName"] == respondent
    assert [a["answerText"] for a in before["entries"][0]["answers"]] == ["Twelve"]

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": target_workshop},
        headers=_as(env, "lead"),
    )
    assert response.status_code == 201, response.text
    payload = response.json()
    copy_id = payload["questionnaire"]["id"]

    assert payload["questionnaire"]["entries"] == [], "a sitting came across with the questions"
    assert payload["report"]["entriesCreated"] == 0
    assert payload["report"]["answersImported"] == 0

    assert _read(client, env, copy_id)["entries"] == []

    workbook = client.get(f"/api/questionnaires/{copy_id}/xlsx", headers=_as(env, "lead"))
    assert workbook.status_code == 200, workbook.text
    assert respondent.encode() not in workbook.content

    assert len(_read(client, env, source["id"])["entries"]) == 1


def test_the_targets_report_annexure_names_no_respondent_from_the_source(client, env):
    source_workshop = _workshop(client, env, f"Annexure source {env['stamp']}")
    target_workshop = _workshop(client, env, f"Annexure target {env['stamp']}")
    source = _questionnaire(
        client, env, f"Annexure survey {env['stamp']}", workshop_id=source_workshop
    )
    respondent = f"Annexure respondent {env['stamp']}"
    _answered_sitting(client, env, source, respondent=respondent)

    reused = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": target_workshop},
        headers=_as(env, "lead"),
    )
    assert reused.status_code == 201, reused.text

    preview = client.get(
        f"/api/design-workshops/{target_workshop}/report/preview", headers=_as(env, "lead")
    )
    assert preview.status_code == 200, preview.text
    assert respondent not in preview.text, "the source's respondent printed in the target's report"

    own = client.get(
        f"/api/design-workshops/{source_workshop}/report/preview", headers=_as(env, "lead")
    )
    assert own.status_code == 200, own.text
    assert respondent in own.text



def test_reuse_into_a_workshop_the_account_cannot_write_to_is_refused(client, env):
    target_workshop = _workshop(client, env, f"Closed workshop {env['stamp']}")
    source = _questionnaire(client, env, f"Open instrument {env['stamp']}")

    assert (
        client.get(
            f"/api/design-workshops/{target_workshop}", headers=_as(env, "outsider")
        ).status_code
        == 404
    )

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": target_workshop, "title": f"Injected form {env['stamp']}"},
        headers=_as(env, "outsider"),
    )
    assert response.status_code == 404, response.text

    attached = client.get(
        f"/api/questionnaires?designWorkshopId={target_workshop}", headers=_as(env, "lead")
    )
    assert attached.status_code == 200, attached.text
    assert attached.json()["total"] == 0

    mine = client.get("/api/questionnaires?mineOnly=true", headers=_as(env, "outsider"))
    assert mine.status_code == 200, mine.text
    assert f"Injected form {env['stamp']}" not in [row["title"] for row in mine.json()["items"]]


def test_removing_the_target_gate_lets_the_injection_straight_back(client, env, monkeypatch):
    import app.api.routes.questionnaire_forms as route

    async def _no_gate(workshop_id, user):  # noqa: ANN001 - mirrors the real signature
        return None

    monkeypatch.setattr(route, "_require_attachable_workshop", _no_gate)

    target_workshop = _workshop(client, env, f"Neutralised target {env['stamp']}")
    source = _questionnaire(client, env, f"Neutralised instrument {env['stamp']}")

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": target_workshop, "title": f"Straight back in {env['stamp']}"},
        headers=_as(env, "outsider"),
    )
    assert response.status_code == 201, "the gate is not what is answering 404 above"
    assert response.json()["questionnaire"]["designWorkshopId"] == target_workshop


def test_a_designer_who_does_not_own_the_source_may_still_reuse_it(client, env):
    source = _questionnaire(client, env, f"Somebody else's instrument {env['stamp']}")
    own_workshop = _workshop(client, env, f"Outsider's own workshop {env['stamp']}", slug="outsider")

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": own_workshop, "title": f"Borrowed instrument {env['stamp']}"},
        headers=_as(env, "outsider"),
    )
    assert response.status_code == 201, response.text
    copy = response.json()["questionnaire"]
    assert copy["ownerId"] == env["people"]["outsider"].id
    assert copy["ownerId"] != source["ownerId"]
    assert _prompts(copy) == _prompts(source)

    reworded = client.patch(
        f"/api/questionnaires/{copy['id']}/questions/{copy['sections'][0]['questions'][0]['id']}",
        json={"prompt": "How many looms are working today?"},
        headers=_as(env, "outsider"),
    )
    assert reworded.status_code == 200, reworded.text
    assert reworded.json()["action"] == "updated"


def test_an_unattached_copy_needs_no_workshop_at_all(client, env):
    source = _questionnaire(client, env, f"Template source {env['stamp']}")

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "outsider")
    )
    assert response.status_code == 201, response.text
    copy = response.json()["questionnaire"]
    assert copy["designWorkshopId"] is None
    assert copy["title"] == f"Template source {env['stamp']} (reused)"

    again = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "outsider")
    )
    assert again.status_code == 201, again.text
    assert again.json()["questionnaire"]["title"] == f"Template source {env['stamp']} (reused 2)"


def test_reuse_at_the_same_workshop_is_allowed(client, env):
    workshop = _workshop(client, env, f"Same workshop {env['stamp']}")
    source = _questionnaire(
        client, env, f"Baseline round {env['stamp']}", workshop_id=workshop
    )

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": workshop},
        headers=_as(env, "lead"),
    )
    assert response.status_code == 201, response.text
    assert response.json()["questionnaire"]["designWorkshopId"] == workshop

    listed = client.get(
        f"/api/questionnaires?designWorkshopId={workshop}", headers=_as(env, "lead")
    )
    assert listed.status_code == 200, listed.text
    assert listed.json()["total"] == 2



def test_editing_the_copy_leaves_the_original_alone(client, env):
    source_workshop = _workshop(client, env, f"Independent source {env['stamp']}")
    target_workshop = _workshop(client, env, f"Independent target {env['stamp']}")
    source = _questionnaire(
        client, env, f"Independent survey {env['stamp']}", workshop_id=source_workshop
    )
    _answered_sitting(client, env, source, respondent=f"Independent respondent {env['stamp']}")
    source_before = _read(client, env, source["id"], retired=True)

    reused = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": target_workshop},
        headers=_as(env, "lead"),
    )
    assert reused.status_code == 201, reused.text
    copy = reused.json()["questionnaire"]
    copy_id = copy["id"]

    first = copy["sections"][0]["questions"][0]
    assert first["hasAnswers"] is False, "the copy inherited an answer, which is the other bug"
    plain = client.patch(
        f"/api/questionnaires/{copy_id}/questions/{first['id']}",
        json={"prompt": "How many looms are in the shed?"},
        headers=_as(env, "lead"),
    )
    assert plain.status_code == 200, plain.text
    assert plain.json()["action"] == "updated"

    copy_form = _read(client, env, copy_id)
    second = copy_form["sections"][0]["questions"][1]
    entry = client.post(
        f"/api/questionnaires/{copy_id}/entries",
        json={"respondentName": f"Copy respondent {env['stamp']}"},
        headers=_as(env, "lead"),
    )
    assert entry.status_code == 201, entry.text
    saved = client.put(
        f"/api/questionnaires/{copy_id}/entries/{entry.json()['id']}/answers",
        json={"answers": [{"questionId": second["id"], "answerText": "Forty years"}]},
        headers=_as(env, "lead"),
    )
    assert saved.status_code == 200, saved.text
    superseded = client.patch(
        f"/api/questionnaires/{copy_id}/questions/{second['id']}",
        json={"prompt": "In which year was the oldest loom built?"},
        headers=_as(env, "lead"),
    )
    assert superseded.status_code == 200, superseded.text
    assert superseded.json()["action"] == "superseded", "the fixture did not exercise the supersede"

    third = _read(client, env, copy_id)["sections"][1]["questions"][0]
    retired = client.delete(
        f"/api/questionnaires/{copy_id}/questions/{third['id']}", headers=_as(env, "admin")
    )
    assert retired.status_code == 200, retired.text

    source_after = _read(client, env, source["id"], retired=True)
    assert _prompts(source_after) == _prompts(source_before), "an edit to the copy reached the source"
    assert source_after["version"] == source_before["version"]
    assert [
        (q["isActive"], q["retiredAt"], q["supersededById"])
        for s in source_after["sections"]
        for q in s["questions"]
    ] == [
        (q["isActive"], q["retiredAt"], q["supersededById"])
        for s in source_before["sections"]
        for q in s["questions"]
    ]
    assert len(source_after["entries"]) == 1
    assert [a["answerText"] for a in source_after["entries"][0]["answers"]] == ["Twelve"]


def test_editing_the_original_leaves_the_copy_alone(client, env):
    workshop = _workshop(client, env, f"Reverse workshop {env['stamp']}")
    source = _questionnaire(
        client, env, f"Reverse survey {env['stamp']}", workshop_id=workshop
    )
    _answered_sitting(client, env, source, respondent=f"Reverse respondent {env['stamp']}")

    reused = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "lead")
    )
    assert reused.status_code == 201, reused.text
    copy_before = _read(client, env, reused.json()["questionnaire"]["id"], retired=True)

    answered = source["sections"][0]["questions"][0]["id"]
    superseded = client.patch(
        f"/api/questionnaires/{source['id']}/questions/{answered}",
        json={"prompt": "How many looms are in use this season?"},
        headers=_as(env, "lead"),
    )
    assert superseded.status_code == 200, superseded.text
    assert superseded.json()["action"] == "superseded", "the fixture did not exercise the supersede"

    copy_after = _read(client, env, copy_before["id"], retired=True)
    assert _prompts(copy_after) == _prompts(copy_before), "an edit to the source reached the copy"
    assert copy_after["version"] == copy_before["version"] == 1
    assert all(
        q["isActive"] and q["retiredAt"] is None and q["supersededById"] is None
        for s in copy_after["sections"]
        for q in s["questions"]
    )



def test_a_second_copy_at_one_workshop_is_numbered_up_past_a_deactivated_one(client, env):
    workshop = _workshop(client, env, f"Rounds workshop {env['stamp']}")
    source = _questionnaire(client, env, f"Rounds {env['stamp']}", workshop_id=workshop)

    first = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": workshop},
        headers=_as(env, "lead"),
    )
    assert first.status_code == 201, first.text
    made = first.json()["questionnaire"]
    assert made["title"] == f"Rounds {env['stamp']} (reused)"

    off = client.patch(
        f"/api/questionnaires/{made['id']}", json={"isActive": False}, headers=_as(env, "lead")
    )
    assert off.status_code == 200, off.text

    second = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": workshop},
        headers=_as(env, "lead"),
    )
    assert second.status_code == 201, second.text
    assert second.json()["questionnaire"]["title"] == f"Rounds {env['stamp']} (reused 2)", (
        "the counter ignored a deactivated row and handed its title to a second one"
    )


def test_an_unattached_copy_is_numbered_against_the_callers_own_templates(client, env):
    source = _questionnaire(client, env, f"Loom survey {env['stamp']}")

    first = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "lead")
    )
    second = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "lead")
    )
    assert first.status_code == 201, first.text
    assert second.status_code == 201, second.text

    assert first.json()["questionnaire"]["title"] == f"Loom survey {env['stamp']} (reused)"
    assert second.json()["questionnaire"]["title"] == f"Loom survey {env['stamp']} (reused 2)", (
        "an unattached copy was named without counting the caller's own templates"
    )
    assert first.json()["questionnaire"]["designWorkshopId"] is None
    assert second.json()["questionnaire"]["designWorkshopId"] is None



def test_a_target_that_does_not_exist_is_a_404_and_writes_nothing(client, env):
    source = _questionnaire(client, env, f"Ghost source {env['stamp']}")
    title = f"Into a ghost {env['stamp']}"

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": "ckzzzzzzzzzzzzzzzzzzzzzzz", "title": title},
        headers=_as(env, "lead"),
    )
    assert response.status_code == 404, response.text
    assert title not in _titles_owned_by(client, env), "an orphan survived the refusal"


def test_a_soft_deleted_target_is_a_409_and_writes_nothing(client, env):
    target = _workshop(client, env, f"Doomed target {env['stamp']}")
    source = _questionnaire(client, env, f"Doomed source {env['stamp']}")
    title = f"Into a deleted workshop {env['stamp']}"

    gone = client.delete(f"/api/design-workshops/{target}", headers=_as(env, "admin"))
    assert gone.status_code == 204, gone.text

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": target, "title": title},
        headers=_as(env, "lead"),
    )
    assert response.status_code == 409, response.text
    assert title not in _titles_owned_by(client, env), "an orphan survived the refusal"


def test_an_empty_string_workshop_id_is_refused_by_the_schema(client, env):
    source = _questionnaire(client, env, f"Blank target source {env['stamp']}")
    title = f"Blank target copy {env['stamp']}"

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": "", "title": title},
        headers=_as(env, "lead"),
    )
    assert response.status_code == 422, response.text
    assert title not in _titles_owned_by(client, env)

    created = client.post(
        "/api/questionnaires",
        json={"title": f"Blank target create {env['stamp']}", "designWorkshopId": ""},
        headers=_as(env, "lead"),
    )
    assert created.status_code == 422, created.text

    unattached = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": None},
        headers=_as(env, "lead"),
    )
    assert unattached.status_code == 201, unattached.text
    assert unattached.json()["questionnaire"]["designWorkshopId"] is None



def test_a_failure_part_way_through_leaves_no_half_copy(client, env, monkeypatch):
    from prisma.actions import QuestionnaireFormQuestionActions

    workshop = _workshop(client, env, f"Half-copy workshop {env['stamp']}")
    source = _questionnaire(client, env, f"Half-copy source {env['stamp']}")
    title = f"Half-copy attempt {env['stamp']}"

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors create_many
        raise RuntimeError("the questions could not be written")

    monkeypatch.setattr(QuestionnaireFormQuestionActions, "create_many", _boom)

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": workshop, "title": title},
        headers=_as(env, "lead"),
    )
    assert response.status_code == 500, response.text

    assert title not in _titles_owned_by(client, env), "a half-written copy was committed"
    attached = client.get(
        f"/api/questionnaires?designWorkshopId={workshop}&activeOnly=false", headers=_as(env, "lead")
    )
    assert attached.status_code == 200, attached.text
    assert attached.json()["total"] == 0, "a half-written copy is attached to the target workshop"



def test_a_source_with_nothing_left_to_copy_still_reads_as_a_sentence(client, env):
    source = _questionnaire(client, env, f"Emptied instrument {env['stamp']}")
    for section in source["sections"]:
        for question in section["questions"]:
            removed = client.delete(
                f"/api/questionnaires/{source['id']}/questions/{question['id']}",
                headers=_as(env, "admin"),
            )
            assert removed.status_code == 200, removed.text

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "lead")
    )
    assert response.status_code == 201, response.text
    report = response.json()["report"]

    assert report["created"] == 0
    reason = report["provenance"]["reason"]
    assert "0 question" not in reason, f"the ungrammatical sentence is back: {reason}"
    assert "no questions in it" in reason
    assert "editing one does not change the other" in reason
    assert "No sitting and no answer was copied" in reason


def test_a_deactivated_source_is_reusable_and_the_copy_is_in_use(client, env):
    source = _questionnaire(client, env, f"Retired instrument {env['stamp']}")
    off = client.patch(
        f"/api/questionnaires/{source['id']}", json={"isActive": False}, headers=_as(env, "lead")
    )
    assert off.status_code == 200, off.text

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "lead")
    )
    assert response.status_code == 201, response.text
    copy = response.json()["questionnaire"]
    assert copy["isActive"] is True
    assert len(_prompts(copy)) == 3


def test_the_description_travels_unless_it_is_explicitly_cleared(client, env):
    source = _questionnaire(
        client, env, f"Described {env['stamp']}", description="Ask this at the loom, not the office."
    )

    carried = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "lead")
    )
    cleared = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"description": None},
        headers=_as(env, "lead"),
    )
    assert carried.status_code == 201, carried.text
    assert cleared.status_code == 201, cleared.text
    assert carried.json()["questionnaire"]["description"] == "Ask this at the loom, not the office."
    assert not cleared.json()["questionnaire"]["description"]


def test_a_source_the_caller_cannot_even_see_carries_no_fieldwork_across(client, env):
    hidden = _workshop(client, env, f"Hidden workshop {env['stamp']}")
    source = _questionnaire(client, env, f"Hidden instrument {env['stamp']}", workshop_id=hidden)
    _answered_sitting(client, env, source, respondent=f"Hidden respondent {env['stamp']}")

    assert (
        client.get(f"/api/design-workshops/{hidden}", headers=_as(env, "outsider")).status_code == 404
    ), "the premise failed: the outsider can see the source's workshop"

    own = _workshop(client, env, f"Outsider workshop {env['stamp']}", slug="outsider")
    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": own, "title": f"Lifted instrument {env['stamp']}"},
        headers=_as(env, "outsider"),
    )
    assert response.status_code == 201, response.text
    copy = _read(client, env, response.json()["questionnaire"]["id"], slug="outsider", retired=True)

    assert len(_prompts(copy)) == 3, "the instrument did not come across"
    assert copy["entries"] == [], "a sitting came across with the questions"
    assert response.json()["report"]["entriesCreated"] == 0
    assert response.json()["report"]["answersImported"] == 0
