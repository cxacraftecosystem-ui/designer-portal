"""Reusing one questionnaire at another workshop: what comes across, what does not, and who may.

THE OWNER'S REQUEST, verbatim: questionnaires "would usually be scoped to the workshops, but the
designers would have the permission to use the same questionnaire later on for a different workshop
as well in case they want to reuse the same template."

``POST /api/questionnaires/{id}/reuse`` answers it by COPYING. There are two shapes that could have
served that sentence and this file exists because only one of them is safe:

* **One questionnaire pointing at MANY workshops** leaks fieldwork. A ``QuestionnaireFormEntry``
  carries ``questionnaireId`` and no workshop at all, while ``report_items`` selects
  ``{"designWorkshopId": ..., "isActive": True}`` with no permission filter and
  ``QUESTIONNAIRE_ANNEXURE`` is in all six report templates — so workshop A's named respondents,
  their notes and their answers would print inside the .docx workshop B submits to a ministry. It
  would also turn ``_works_on_this_questionnaires_workshop``, which reads a SINGULAR
  ``designWorkshopId``, into "any of n", so one viewer grant would admit its holder to every sitting
  at every attached workshop.
* **A copy** — two rows, two question trees, two histories. What this file measures.

FOUR PROPERTIES, one test each, and each of them is a thing a plausible implementation gets wrong:

1. THE COPY CARRIES THE INSTRUMENT. Sections, questions, order, help text, required flags.
2. THE COPY CARRIES NO FIELDWORK. Zero sittings and zero answers, with the source deliberately
   holding an ANSWERED sitting so "no entries" cannot be true merely because there were none. This
   is the non-negotiable: a reuse that carried the answers across would duplicate somebody's
   interviews under a new author, into a second workshop's ministry annexure.
3. THE TARGET IS GATED. A workshop the account cannot write to is refused, and nothing survives the
   refusal — no orphan questionnaire, and no row the report of that workshop would print.
4. THE TWO ARE INDEPENDENT. Editing the copy leaves the original's questions, its version and its
   recorded answers exactly as they were. This is the property the join-table design cannot have,
   and the reason the copy's cost (divergence) was accepted.

5. THE COPY IS ALL OF IT OR NONE OF IT. The row, its sections and its questions go in one
   transaction, because a copy that got half way is indistinguishable from a whole one — the designer
   sees an error, retries, and owns a truncated instrument beside a complete "(reused 2)" with
   nothing on any screen able to say which is short.
6. THE NAME IS THE ONE THE DIALOG PREVIEWED, in both destinations. ``reuse_title`` counts up against
   the titles at the TARGET, and "no target" is a destination too — the caller's own unattached
   templates. ``ReuseDialog`` mirrors this counting to pre-fill its title box, so a change here
   silently makes that preview wrong.
7. A BAD BODY IS A 422. An empty-string ``designWorkshopId`` skipped the truthiness-guarded workshop
   check and reached Prisma as a foreign key, measured as a 500. Nothing was authorized by it and no
   orphan survived it, but a 500 invites a retry that cannot work.

Plus the two controls without which the four above could pass for the wrong reasons: reuse must WORK
for a designer who does not own the source (the instrument is already open to any designer through
``/question-set.xlsx``, so a blanket ``_require_owner`` here would be an over-tight fix), and
neutralising the ONE gate the route adds must put the refusal in (3) straight back — otherwise a 404
arriving for some unrelated reason would pass as a permission check.

These need Postgres: what is under test is which rows a request leaves behind. The module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_questionnaire_write_path_access`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

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
    """A lead designer, an unrelated designer, an admin, and a live TestClient.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.

    THE OUTSIDER IS A DESIGNER, not a researcher, and that is what makes this file measure anything.
    ``_require_designer`` admits every DESIGNER account, so an outsider of a lower rank would be
    refused by the role gate and every assertion below would pass against a route with no workshop
    check in it at all.

    THE ROSTER ROWS ARE LOAD-BEARING: ``replace_viewers`` refuses an account the ACTIVE designer
    roster does not admit, with a 422 rather than a silent skip, so without them the grant that makes
    the lead designer able to work on their own workshop would not exist and "the outsider is
    refused" could be explained by the route refusing everybody.
    """
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

    # ONE TestClient, three tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp}


@pytest.fixture
def client(env):
    return env["client"]


def _as(env: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


#: Two sections and three questions, so "the copy carries the instrument" is a claim about a TREE
#: rather than about one row — a copier that flattened both sections into one, or that dropped a
#: section's second question, would pass a one-question fixture.
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
    """A workshop belonging to *slug*, opened the way workshops are actually opened.

    An ADMIN creates it and then GRANTS the designer, because only admins and the master admin may
    START a design workshop (``can_create_design_workshops``) — a workshop is the container a
    fortnight of records lives in, not a record. Posting as the designer answers 403, which would
    fail every test in this module on its first line for a reason unrelated to reuse.
    """
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
    """Every title this account owns, active or not — used to prove a refusal left NO row.

    ``mineOnly`` is ``ownerId = me``, which is where an orphan from a refused reuse would land: the
    copy is created owned by the CALLER, so a row that survived a refusal is in nobody else's list.
    ``activeOnly=false`` because a row nobody can see is still a row.
    """
    response = client.get(
        "/api/questionnaires?mineOnly=true&activeOnly=false&pageSize=100", headers=_as(env, slug)
    )
    assert response.status_code == 200, response.text
    return [row["title"] for row in response.json()["items"]]


def _prompts(form: dict) -> list[tuple[str, str]]:
    """Every (section code, prompt) in the form, in the order it is served.

    A LIST OF PAIRS, not a set of prompts: reuse has to keep a question inside the section it was
    asked in, and a set would let a copier put every question under section A and still pass.
    """
    return [
        (section["code"], question["prompt"])
        for section in form["sections"]
        for question in section["questions"]
    ]


def _answered_sitting(client, env, form: dict, *, respondent: str, slug: str = "lead") -> str:
    """One sitting on *form* with a real answer in it. Returns the entry id."""
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


# --------------------------------------------------------------------------------------
# 1. The copy carries the instrument
# --------------------------------------------------------------------------------------


def test_the_copy_carries_every_section_and_question_in_its_own_section(client, env):
    """The whole tree, in order, with help text and required flags — and NEW ids.

    The ids matter in both directions. They must DIFFER, because two questionnaires sharing question
    rows is the join-table design this one rejected; and the copy's own questions must be readable
    under the copy, because a reuse whose questions still belonged to the source would be that design
    wearing a copy's clothes.
    """
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
    # A fresh row's history starts at 1: the copy has had no edit applied to it after any answer.
    assert copy["version"] == 1
    # No spreadsheet produced this row, and naming one would send a designer off to edit a file that
    # made something else.
    assert copy["sourceFilename"] is None

    assert _prompts(copy) == _prompts(source), "the instrument did not come across intact"
    assert copy["questionCount"] == source["questionCount"] == 3

    first = copy["sections"][0]["questions"][0]
    assert first["helpText"] == "Working looms only."
    assert first["isRequired"] is True
    # Retirement machinery is NOT copied: a ``supersededById`` carried across would point a row in
    # the copy at a row in the SOURCE, which is the cross-questionnaire pointer ``_question_in``
    # exists to prevent.
    assert first["supersededById"] is None
    assert first["retiredAt"] is None

    source_ids = {q["id"] for _, q in [(s, q) for s in source["sections"] for q in s["questions"]]}
    copy_ids = {q["id"] for s in copy["sections"] for q in s["questions"]}
    assert not (source_ids & copy_ids), "the copy is sharing question rows with the source"

    # The report shape the client already renders, and the sentence it renders verbatim.
    report = payload["report"]
    assert report["created"] == 3
    assert report["sections"] == 2
    assert report["problems"] == []
    assert report["provenance"]["action"] == "reused"
    assert report["provenance"]["sourceQuestionnaireId"] == source["id"]
    assert report["provenance"]["answersSkipped"] == 0
    assert payload["sourceQuestionnaireId"] == source["id"]


def test_retired_questions_do_not_come_across(client, env):
    """A retired question is kept for the answers hanging off it, not because it is still asked.

    Copying one would plant a question the designer DELIBERATELY replaced into a brand-new
    instrument — ``build_question_set_workbook``'s argument, applied to the same content travelling
    by a different door. The source's own retired row must survive the reuse untouched, which is the
    second half of this test.
    """
    source = _questionnaire(client, env, f"Retire source {env['stamp']}")
    doomed = source["sections"][1]["questions"][0]["id"]
    # Answered first, so DELETE retires rather than really deleting — the case worth measuring.
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
        f"/api/questionnaires/{source['id']}/questions/{doomed}", headers=_as(env, "lead")
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

    # The retired question and its answer are still on the SOURCE, where they belong.
    still = _read(client, env, source["id"], retired=True)
    assert doomed in {q["id"] for s in still["sections"] for q in s["questions"]}
    assert any(a["answerText"] == "The town market" for e in still["entries"] for a in e["answers"])


# --------------------------------------------------------------------------------------
# 2. The copy carries no fieldwork — the non-negotiable
# --------------------------------------------------------------------------------------


def test_the_copy_arrives_with_no_sitting_and_no_answer(client, env):
    """THE PROPERTY THIS FEATURE LIVES OR DIES ON.

    The source deliberately holds an ANSWERED sitting with a respondent's NAME on it, so "the copy
    has no entries" cannot be true merely because there were none to copy. A reuse that carried them
    would duplicate somebody's interview under a new author and push it into a second workshop's
    ministry annexure — which is precisely the defect ``create_from_parsed`` was changed to stop,
    reached by a different door.

    ``reuse_questionnaire`` reads the source through ``load_question_set``, which never issues the
    entry or answer queries at all, so this is a property of the QUERY and not of a filter somebody
    could forget. The last assertion counts rows through the API rather than trusting the payload's
    shape, because a payload can omit a key the database still holds.
    """
    source_workshop = _workshop(client, env, f"Fieldwork source {env['stamp']}")
    target_workshop = _workshop(client, env, f"Fieldwork target {env['stamp']}")
    source = _questionnaire(
        client, env, f"Answered survey {env['stamp']}", workshop_id=source_workshop
    )
    respondent = f"Ramesh {env['stamp']}"
    _answered_sitting(client, env, source, respondent=respondent)

    # The premise: there IS fieldwork on the source.
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

    # Read back independently of the create response, and read as the OWNER — who sees every sitting
    # there is, so an empty list here is emptiness rather than ``only_entries_of`` narrowing.
    assert _read(client, env, copy_id)["entries"] == []

    # And the respondent's name is nowhere in the copy's own lossless export, which is the artefact
    # that would carry a leaked sitting out of the system.
    workbook = client.get(f"/api/questionnaires/{copy_id}/xlsx", headers=_as(env, "lead"))
    assert workbook.status_code == 200, workbook.text
    assert respondent.encode() not in workbook.content

    # The original still holds its own fieldwork: this is a copy, not a move.
    assert len(_read(client, env, source["id"])["entries"]) == 1


def test_the_targets_report_annexure_names_no_respondent_from_the_source(client, env):
    """THE CONSEQUENCE, not just the row count.

    ``report_items`` selects on ``{"designWorkshopId": ..., "isActive": True}`` and
    ``QUESTIONNAIRE_ANNEXURE`` is in all six report templates, so a sitting that HAD come across
    would print in the .docx the target workshop submits to a ministry. This is the assertion the
    join-table design fails, and it fails it without any permission check being wrong anywhere.
    """
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

    # The control: the source's OWN workshop does still print them, so the assertion above is about
    # the copy and not about the annexure being empty for everybody.
    own = client.get(
        f"/api/design-workshops/{source_workshop}/report/preview", headers=_as(env, "lead")
    )
    assert own.status_code == 200, own.text
    assert respondent in own.text


# --------------------------------------------------------------------------------------
# 3. The target workshop is gated
# --------------------------------------------------------------------------------------


def test_reuse_into_a_workshop_the_account_cannot_write_to_is_refused(client, env):
    """A fourth attachment route, asking the same question the other three ask.

    ``_require_attachable_workshop`` → ``load_workshop_or_404(..., for_edit=True)``: creator, admin,
    or viewer grant. 404 rather than 403 is inherited from the helper and is the right answer for the
    helper's reason — a 403 would confirm the workshop id exists to exactly the caller being turned
    away.

    NOTHING MAY SURVIVE THE REFUSAL. The check runs before any row is written, so there must be no
    orphan questionnaire owned by the outsider either — a 404 handed back alongside a created row
    reads to the caller as "it failed" and is not.
    """
    target_workshop = _workshop(client, env, f"Closed workshop {env['stamp']}")
    source = _questionnaire(client, env, f"Open instrument {env['stamp']}")

    # The premise: to this designer the target workshop does not exist.
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

    # No row anywhere: not at the workshop, and not as an unattached orphan in the outsider's list.
    attached = client.get(
        f"/api/questionnaires?designWorkshopId={target_workshop}", headers=_as(env, "lead")
    )
    assert attached.status_code == 200, attached.text
    assert attached.json()["total"] == 0

    mine = client.get("/api/questionnaires?mineOnly=true", headers=_as(env, "outsider"))
    assert mine.status_code == 200, mine.text
    assert f"Injected form {env['stamp']}" not in [row["title"] for row in mine.json()["items"]]


def test_removing_the_target_gate_lets_the_injection_straight_back(client, env, monkeypatch):
    """THE OLD BEHAVIOUR, REPRODUCED IN PROCESS, so the test above cannot rot into a tautology.

    A test that only asserts 404 passes just as well against a route that 404s for an unrelated
    reason. Neutralising the ONE call this route makes must put the defect back — 201, and a
    stranger's questionnaire attached to a workshop they cannot open — and if it does not, the
    assertions above have stopped measuring the gate.

    Patched on the module rather than by editing the file: the working tree is shared, and a route
    with its authorization check commented out is not a thing to leave lying around for even one
    test run.
    """
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
    """THE HALF AN OVER-TIGHT FIX WOULD BREAK, and the reason ``_require_owner`` is not on this route.

    The INSTRUMENT already leaves this system for any designer: ``GET /{id}/question-set.xlsx`` is
    gated exactly as READING the form is, on the stated ground that the questions are the openly
    readable half. Refusing here would refuse in JSON precisely what that door hands over as a
    file — and be routed around by downloading it and uploading it, producing the same row with NO
    provenance recorded at all.

    The copy belongs to the CALLER, not to the source's owner, because ``_require_owner`` governs
    rewording and a copy its maker cannot reword is not a reuse.
    """
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

    # And they can reword their own copy, which is the point of owning it.
    reworded = client.patch(
        f"/api/questionnaires/{copy['id']}/questions/{copy['sections'][0]['questions'][0]['id']}",
        json={"prompt": "How many looms are working today?"},
        headers=_as(env, "outsider"),
    )
    assert reworded.status_code == 200, reworded.text
    assert reworded.json()["action"] == "updated"


def test_an_unattached_copy_needs_no_workshop_at_all(client, env):
    """An empty body is a meaningful request: a template the caller owns, attached to nothing.

    No workshop is named, so no workshop check is asked for — an unattached questionnaire is visible
    only under ``ownerId = me`` in ``_visible_questionnaire_where`` and ``report_items`` cannot reach
    a row with a NULL ``designWorkshopId``. The default title is what makes the row findable, so it
    is asserted rather than assumed.
    """
    source = _questionnaire(client, env, f"Template source {env['stamp']}")

    response = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "outsider")
    )
    assert response.status_code == 201, response.text
    copy = response.json()["questionnaire"]
    assert copy["designWorkshopId"] is None
    assert copy["title"] == f"Template source {env['stamp']} (reused)"

    # Reused again into the same place: the default counts itself up rather than producing a second
    # row nobody can tell from the first.
    again = client.post(
        f"/api/questionnaires/{source['id']}/reuse", json={}, headers=_as(env, "outsider")
    )
    assert again.status_code == 201, again.text
    assert again.json()["questionnaire"]["title"] == f"Template source {env['stamp']} (reused 2)"


def test_reuse_at_the_same_workshop_is_allowed(client, env):
    """A follow-up round of one instrument at one workshop, which a sitting has no notion of.

    Refusing would be both wrong and pointless: a designer can walk round it in two clicks
    ("Download question set", then upload) and get the identical row with no provenance recorded.
    The dialog cautions before the press; the API does not refuse.
    """
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


# --------------------------------------------------------------------------------------
# 4. The two are independent — the property the join table cannot have
# --------------------------------------------------------------------------------------


def test_editing_the_copy_leaves_the_original_alone(client, env):
    """ONE WORKSHOP EDITING ITS COPY MUST NOT CHANGE ANOTHER WORKSHOP'S COPY.

    Three edits, each of which the join-table design would have leaked into the source in a DIFFERENT
    way, so one of them succeeding by accident cannot hide the other two:

    * REWORDING an UNANSWERED question — a plain overwrite, which under a shared row would silently
      change the wording of a live question at the other workshop;
    * REWORDING an ANSWERED question — which SUPERSEDES, and under a shared row would ADD a question
      to the other workshop's live form that nobody there wrote;
    * RETIRING a question — which under a shared row would stop the other workshop asking it
      mid-fieldwork.

    The source's ``version`` is asserted too: it counts edits made after answers existed, so a shared
    row would show the copy's supersede as an edit to the original.
    """
    source_workshop = _workshop(client, env, f"Independent source {env['stamp']}")
    target_workshop = _workshop(client, env, f"Independent target {env['stamp']}")
    source = _questionnaire(
        client, env, f"Independent survey {env['stamp']}", workshop_id=source_workshop
    )
    # An answer on the SOURCE's first question, so the copy's matching question is answered-free
    # while the source's is not — which is what makes the supersede branch below run on the copy only.
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

    # (a) reword an unanswered question on the copy
    first = copy["sections"][0]["questions"][0]
    assert first["hasAnswers"] is False, "the copy inherited an answer, which is the other bug"
    plain = client.patch(
        f"/api/questionnaires/{copy_id}/questions/{first['id']}",
        json={"prompt": "How many looms are in the shed?"},
        headers=_as(env, "lead"),
    )
    assert plain.status_code == 200, plain.text
    assert plain.json()["action"] == "updated"

    # (b) answer one on the copy, then reword it, so the SUPERSEDE branch runs here and not there
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

    # (c) retire a question on the copy
    third = _read(client, env, copy_id)["sections"][1]["questions"][0]
    retired = client.delete(
        f"/api/questionnaires/{copy_id}/questions/{third['id']}", headers=_as(env, "lead")
    )
    assert retired.status_code == 200, retired.text

    # THE ORIGINAL, UNCHANGED IN EVERY RESPECT THAT WAS TOUCHED ON THE COPY.
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
    # Its fieldwork too: still one sitting, still one answer, still the same text.
    assert len(source_after["entries"]) == 1
    assert [a["answerText"] for a in source_after["entries"][0]["answers"]] == ["Twelve"]


def test_editing_the_original_leaves_the_copy_alone(client, env):
    """The other direction, and NOT a mirror image worth skipping.

    The two copies are not symmetrical: the SOURCE is the one with fieldwork already recorded against
    it, so an edit there runs the supersede/retire branches that write ``supersededById`` and
    ``retiredAt`` — the two columns a copier is most likely to have carried across. If the copy had
    inherited either, an edit here is where it would show.
    """
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


# --------------------------------------------------------------------------------------
# 5. The name the copy is given — the half ``ReuseDialog`` mirrors
# --------------------------------------------------------------------------------------


def test_a_second_copy_at_one_workshop_is_numbered_up_past_a_deactivated_one(client, env):
    """The counting: "X (reused)", then "X (reused 2)", counting DEACTIVATED neighbours too.

    Two rows sharing a title at one workshop are two rows nobody can tell apart in that workshop's
    report annexure, which is the artefact this whole feature ends up inside.

    THE DEACTIVATED HALF IS THE PART WORTH PINNING. ``reuse_title`` deliberately does not filter
    ``isActive`` when it gathers the names already there: "a deactivated form is hidden from the lists
    but its title is still the title of a row somebody may bring back into use, and colliding with it
    would be a collision that appears later, out of nowhere." ``ReuseDialog`` mirrors that by passing
    ``activeOnly: false`` to the list endpoint, whose own default is TRUE — so if this rule is ever
    relaxed here, that override becomes the thing that is wrong and nothing else would say so.
    """
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

    # OUT OF USE, which is this API's stand-in for a delete — and still the owner of its title.
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
    """"NO WORKSHOP" IS ALSO A DESTINATION, and the server counts there too.

    ``reuse_title``'s neighbours are ``{designWorkshopId: None, ownerId: me}`` when no target is
    named — this designer's own unattached templates, "the only place an unattached copy shows up".

    PINNED BECAUSE THE DIALOG PREVIEWS IT. "Don't attach it yet" is ``ReuseDialog``'s default, and its
    look-up used to early-return on a falsy workshop id: the box showed "X (reused)" while the server
    was about to write "X (reused 2)", and the amber collision warning stayed silent for a typed title
    that really did duplicate an existing template. The client now asks with ``mineOnly`` and filters
    to a NULL ``designWorkshopId``, because neither side can express "attached to nothing" as a query
    parameter — both drop the key when it is falsy.
    """
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


# --------------------------------------------------------------------------------------
# 6. Refusals leave nothing behind, and a bad body is a 422
# --------------------------------------------------------------------------------------


def test_a_target_that_does_not_exist_is_a_404_and_writes_nothing(client, env):
    """A ghost id is the same answer as a workshop the caller may not see, deliberately.

    ``load_workshop_or_404`` gives one detail string for both, so the refusal cannot be read as
    "that workshop exists and you are not on it".
    """
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
    """A DELETED workshop the caller may otherwise write to is 409, not 404 and not a silent write.

    The distinction is the point: 404 means "not yours to write to", 409 means "yours, but deleted —
    restore it first", and both must leave the tables exactly as they were. ``_require_attachable_workshop``
    runs before the first write, so there is nothing to roll back.
    """
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
    """AN EMPTY STRING IS NOT A WORKSHOP ID, and used to be treated as one all the way down to Postgres.

    Every route that takes this field guards it with ``if payload.designWorkshopId:``, so the empty
    string SKIPPED the workshop authorization check and travelled on as a foreign key —
    ``500 {"error":"ForeignKeyViolationError"}``. No authorization was bypassed (the FK refuses, and
    the ``Questionnaire`` create is the first write, so no orphan survives) but a 500 tells a client
    to retry something that cannot ever succeed.

    ``min_length=1`` is on the same field in ``QuestionnaireCreate`` and ``QuestionnaireUpdate``, so
    the plain create is measured here too: this was one defect behind three doors, and fixing the new
    door alone would have left the older two answering 500.
    """
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

    # NULL still means "not attached", which is the case a `min_length` applied to the wrong branch
    # would have broken — and the detach path on the PATCH depends on it.
    unattached = client.post(
        f"/api/questionnaires/{source['id']}/reuse",
        json={"designWorkshopId": None},
        headers=_as(env, "lead"),
    )
    assert unattached.status_code == 201, unattached.text
    assert unattached.json()["questionnaire"]["designWorkshopId"] is None


# --------------------------------------------------------------------------------------
# 7. All of it or none of it
# --------------------------------------------------------------------------------------


def test_a_failure_part_way_through_leaves_no_half_copy(client, env, monkeypatch):
    """THE COPY IS ONE TRANSACTION, measured by breaking the last write in it.

    Before this, the copy was a bare create plus one create per section and one per question — up to
    2200 sequential statements with no transaction round them, against a database in another region.
    A request that died at question 900 of 2000 left the ``Questionnaire`` row and 900 questions
    COMMITTED, attached to the target workshop and owned by the caller, while the client showed an
    error. The designer retries, and now owns a truncated instrument beside a complete "(reused 2)"
    with nothing on any screen able to say which of the two is short.

    THE QUESTION WRITE IS BROKEN AT THE PRISMA ACTION CLASS, not on the module-level ``db``, and that
    is the whole trick: ``db.tx()`` hands back a DIFFERENT client, so a patch on ``db`` would not be
    inside the transaction at all and this test would measure nothing. Patching the generated action
    class reaches whichever client is holding it.

    ── THE FIXTURES ARE BUILT BEFORE THE PATCH, AND SINCE 2026-08-30 THAT ORDER IS LOAD-BEARING ──

    The patch used to be applied first, which was harmless while ``POST /api/questionnaires`` wrote
    its questions one ``create`` at a time: a break on ``create_many`` reached the reuse path and
    nothing else. That create is now ONE TRANSACTION batched through the very same ``create_many``
    (see ``create_questionnaire``, and D2 in this feature's lane brief), so an early patch blew up
    the SOURCE questionnaire this test needs — the run failed at line 980 with
    ``500 RuntimeError: the questions could not be written`` before reaching the reuse it is about.

    Moving the two fixture lines above the patch restores exactly what this test measured and
    narrows nothing: the ``monkeypatch`` is still live for the whole of the reuse call, which is the
    only write under test. Anything else this file adds to a fixture from here on must be built
    above the patch for the same reason.
    """
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

    # NOTHING: no questionnaire row, at the target or in the caller's own list. A row here would be a
    # questionnaire with sections and no questions, indistinguishable in every list from a complete
    # one.
    assert title not in _titles_owned_by(client, env), "a half-written copy was committed"
    attached = client.get(
        f"/api/questionnaires?designWorkshopId={workshop}&activeOnly=false", headers=_as(env, "lead")
    )
    assert attached.status_code == 200, attached.text
    assert attached.json()["total"] == 0, "a half-written copy is attached to the target workshop"


# --------------------------------------------------------------------------------------
# 8. What the copy says about itself
# --------------------------------------------------------------------------------------


def test_a_source_with_nothing_left_to_copy_still_reads_as_a_sentence(client, env):
    """The sentence "carrying the 0 questions of “X”" was reachable, and it is shown to designers VERBATIM.

    ``provenance.reason`` is written on the server to be printed as it stands — four places in the
    stack could paraphrase it and the one that did would cost a designer their understanding of who
    owns what — so it has to be a sentence in every reachable state. A source whose questions have all
    gone is one of them, and it arrives from two directions (every question retired, or none ever
    added), so the wording may not claim retirement either.
    """
    source = _questionnaire(client, env, f"Emptied instrument {env['stamp']}")
    for section in source["sections"]:
        for question in section["questions"]:
            removed = client.delete(
                f"/api/questionnaires/{source['id']}/questions/{question['id']}",
                headers=_as(env, "lead"),
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
    # The two facts the whole message exists for survive the empty case.
    assert "editing one does not change the other" in reason
    assert "No sitting and no answer was copied" in reason


def test_a_deactivated_source_is_reusable_and_the_copy_is_in_use(client, env):
    """``isActive: false`` IS THIS API'S DELETE, and a retired instrument is exactly what gets lifted.

    Refusing here would force a designer to reactivate the old form first — putting it back in every
    list and every dropdown for everyone — in order to make a copy. The copy itself is in use from the
    start: it inherits the questions, not the source's retirement.
    """
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
    """The tri-state the route unpacks by hand, and the reason it does.

    ``exclude_unset`` is what tells "carry the source's description across" (key absent) from "start it
    empty" (key sent as null) — a distinction ``clean_data`` cannot make, because it drops nulls. The
    description is part of how an instrument READS, so the default is to carry it.
    """
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
    """THE STRONGEST FORM OF THE OPEN-INSTRUMENT RULE, and the strongest form of the leak it must not be.

    The source here is attached to a workshop the outsider is 404'd from and holds an ANSWERED sitting
    with a named respondent. The reuse is allowed — ``/question-set.xlsx`` already hands the questions
    to any designer, and refusing in JSON what a file hands over would be routed around by downloading
    it — but the copy must arrive with nothing of that sitting in it, into the outsider's own workshop.
    """
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
