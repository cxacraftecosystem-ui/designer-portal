"""Who may record a SITTING against a questionnaire that is attached to a design workshop.

THE HALF-CLOSED DOOR THIS PINS. The CRITICAL audit finding of 2026-08-15 was that any designer could
ATTACH a questionnaire to any other designer's workshop; the fix put
``load_workshop_or_404(..., for_edit=True)`` on all three routes that set ``designWorkshopId`` and
stopped there. The finding itself said, in its own words, that this "closes only the ATTACHMENT
door", and the write door stayed open: ``POST /questionnaires/{id}/entries`` and
``PUT /questionnaires/{id}/entries/{eid}/answers`` were gated by ``_require_questionnaire`` alone —
"is a designer" plus "the row exists" — so a designer holding the id of a form that is ALREADY
attached to somebody else's workshop could add a sitting with a respondent's name on it and a full
set of answers.

That is not a stray row. ``report_items`` selects on ``designWorkshopId`` with no permission filter
and ``QUESTIONNAIRE_ANNEXURE`` is in all six report templates, so the fabricated interview prints
inside the .docx another designer submits to a ministry under their own name. The id is not a secret
either: the read docstring in the route module names the ways a stranger comes by one — a shared
link, a browser history entry, a report annexure.

WHAT THIS FILE HAS TO PROVE, and why each half is here:

  * the write path refuses a designer with no relationship to the attached workshop,
  * the ordinary cases still work — the owner writing to their OWN attached form, and ANY designer
    writing to an UNATTACHED one, which is the "hand a colleague a form to fill in" case the route's
    own docstring is built around and the case an over-tight fix would break,
  * the gate is the workshop grant and nothing blunter: a granted co-designer writes, and the same
    account writes no more once the grant is taken away — which is the property the finding asked
    for, "revoking a viewer row takes effect on writes as it already does on reads",
  * neutralising the one call the fix added puts the hole back exactly as described, so this file
    cannot quietly stop measuring anything.

These need Postgres: what is under test is which rows a request leaves behind. The module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_process_step_authorization`` does.

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
    """A lead designer, an outsider, a colleague to be granted, an admin, and a live TestClient.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.

    EVERY ACCOUNT BUT THE ADMIN IS A DESIGNER, on purpose. DESIGNER is the rank
    ``can_run_design_workshops`` admits, so all three pass the only gate the write path used to
    have; a test written with an admin would pass against the unfixed code and prove nothing.

    THE ROSTER ROWS ARE LOAD-BEARING. ``replace_viewers`` refuses an account the ACTIVE designer
    roster does not admit, with a 422 rather than a silent skip, and the control below has to be
    able to hand out a REAL grant — otherwise "the outsider is refused" could be explained by the
    route refusing everybody, which is the failure mode of an over-tight fix.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in (
            ("lead", "DESIGNER", "Workshop Lead Designer"),
            ("outsider", "DESIGNER", "Unrelated Designer"),
            ("colleague", "DESIGNER", "Co-designer To Be Granted"),
            ("admin", "ADMIN", "Questionnaire Admin"),
        ):
            people[slug] = await db.user.create(
                data={
                    "email": f"qwrite-{slug}-{stamp}@example.org",
                    "name": name,
                    "role": role,
                }
            )
        for slug in ("lead", "outsider", "colleague"):
            await db.designerroster.create(
                data={
                    "email": f"qwrite-{slug}-{stamp}@example.org",
                    "fullName": people[slug].name,
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
    finally:
        await db.disconnect()

    # ONE TestClient, four tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp}


@pytest.fixture
def client(env):
    return env["client"]


def _as(env: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


ONE_QUESTION = [
    {"code": "A", "title": "About the loom", "questions": [{"prompt": "How many looms?"}]}
]


def _workshop(client, env, title: str, slug: str = "lead") -> str:
    response = client.post(
        "/api/design-workshops", json={"title": title}, headers=_as(env, slug)
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _questionnaire(client, env, title: str, *, workshop_id: str | None, slug: str = "lead") -> dict:
    body: dict[str, Any] = {"title": title, "sections": ONE_QUESTION}
    if workshop_id is not None:
        body["designWorkshopId"] = workshop_id
    response = client.post("/api/questionnaires", json=body, headers=_as(env, slug))
    assert response.status_code == 201, response.text
    form = response.json()
    assert form.get("designWorkshopId") == workshop_id
    return form


def _question_id(form: dict) -> str:
    return form["sections"][0]["questions"][0]["id"]


def _entries(client, env, questionnaire_id: str, slug: str = "lead") -> list[dict]:
    """The sittings, read as the form's OWNER sees them — which is every sitting there is."""
    response = client.get(f"/api/questionnaires/{questionnaire_id}", headers=_as(env, slug))
    assert response.status_code == 200, response.text
    return response.json()["entries"]


# --------------------------------------------------------------------------------------
# 1. The door the attachment fix left open
# --------------------------------------------------------------------------------------


def test_a_stranger_cannot_start_a_sitting_on_a_form_attached_to_another_workshop(client, env):
    """The injection in its shortest form: one signed-in designer, one POST, a respondent's name
    inside another team's fieldwork.

    404 rather than 403 is inherited from ``load_workshop_or_404`` and is the right answer for the
    same reason it is there: a 403 would confirm the workshop id exists to exactly the caller being
    turned away.
    """
    workshop_id = _workshop(client, env, f"Lead's workshop {env['stamp']}")
    form = _questionnaire(
        client, env, f"Attached form {env['stamp']}", workshop_id=workshop_id
    )

    # The premise: to this designer the workshop does not exist.
    assert (
        client.get(
            f"/api/design-workshops/{workshop_id}", headers=_as(env, "outsider")
        ).status_code
        == 404
    )

    response = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": f"Fabricated respondent {env['stamp']}"},
        headers=_as(env, "outsider"),
    )

    assert response.status_code == 404, response.text
    assert _entries(client, env, form["id"]) == [], "a sitting survived the refusal"


def test_a_stranger_cannot_record_answers_into_a_sitting_of_an_attached_form(client, env):
    """The second write route. Refusing only ``create_entry`` would leave every sitting the
    workshop's own team has started open to a stranger's answers — and ``save_answers``' authorship
    rule does not help, because it only protects an answer that ALREADY has text.
    """
    workshop_id = _workshop(client, env, f"Answers workshop {env['stamp']}")
    form = _questionnaire(
        client, env, f"Answerable form {env['stamp']}", workshop_id=workshop_id
    )
    entry = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Ramesh"},
        headers=_as(env, "lead"),
    )
    assert entry.status_code == 201, entry.text
    entry_id = entry.json()["id"]

    response = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Ninety"}]},
        headers=_as(env, "outsider"),
    )

    assert response.status_code == 404, response.text
    sittings = _entries(client, env, form["id"])
    assert len(sittings) == 1
    assert sittings[0]["answers"] == [], "an answer survived the refusal"


def test_nothing_a_stranger_sends_reaches_the_workshops_report(client, env):
    """THE CONSEQUENCE, not just the status code. The annexure is what makes this worth a CRITICAL:
    ``report_items`` selects purely on ``designWorkshopId``, so a sitting that got in would print in
    the .docx the lead designer submits under their own name, warning neither party.
    """
    workshop_id = _workshop(client, env, f"Report workshop {env['stamp']}")
    form = _questionnaire(
        client, env, f"Report form {env['stamp']}", workshop_id=workshop_id
    )
    marker = f"Injected respondent {env['stamp']}"

    refused = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": marker, "title": marker},
        headers=_as(env, "outsider"),
    )
    assert refused.status_code == 404, refused.text

    preview = client.get(
        f"/api/design-workshops/{workshop_id}/report/preview", headers=_as(env, "lead")
    )
    assert preview.status_code == 200, preview.text
    assert marker not in preview.text


def test_removing_the_write_gate_brings_the_injection_straight_back(client, env, monkeypatch):
    """THE OLD BEHAVIOUR, REPRODUCED IN PROCESS, so the assertions above cannot rot into
    tautologies.

    A test that only asserts 404 passes just as well against a route that 404s for some unrelated
    reason. Neutralising the ONE call this fix added must put the defect back exactly as the audit
    described it — 201, and the stranger's sitting sitting inside the lead designer's questionnaire
    — and if it does not, the assertions above have stopped measuring the gate.

    Patched on the module rather than by editing the file: the working tree is shared, and a route
    with its authorization check commented out is not a thing to leave lying around for even one
    test run.
    """
    import app.api.routes.questionnaire_forms as route

    async def _no_gate(record, user):  # noqa: ANN001 - mirrors the real signature
        return None

    monkeypatch.setattr(route, "_require_recordable_questionnaire", _no_gate)

    workshop_id = _workshop(client, env, f"Neutralised workshop {env['stamp']}")
    form = _questionnaire(
        client, env, f"Neutralised form {env['stamp']}", workshop_id=workshop_id
    )

    response = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Straight back in"},
        headers=_as(env, "outsider"),
    )

    assert response.status_code == 201, "the gate is not what is answering 404 above"
    assert [e["respondentName"] for e in _entries(client, env, form["id"])] == ["Straight back in"]


# --------------------------------------------------------------------------------------
# 2. The half a blanket refusal would break
#
# Every assertion below FAILS if the gate is written as "only the owner may write", which is the
# obvious over-tight reading of the finding. Recording answers is the whole point of a questionnaire
# and a form only its author may answer is a form nobody uses.
# --------------------------------------------------------------------------------------


def test_the_owner_still_records_sittings_and_answers_on_their_own_attached_form(client, env):
    """The ordinary case, and the one the whole feature exists for: the designer who runs the
    workshop filling in the questionnaire attached to it. They pass as the workshop's CREATOR, not
    as the questionnaire's owner — the gate asks about the workshop, deliberately."""
    workshop_id = _workshop(client, env, f"Own workshop {env['stamp']}")
    form = _questionnaire(client, env, f"Own form {env['stamp']}", workshop_id=workshop_id)

    entry = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Sita Devi"},
        headers=_as(env, "lead"),
    )
    assert entry.status_code == 201, entry.text

    answers = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry.json()['id']}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Twelve"}]},
        headers=_as(env, "lead"),
    )
    assert answers.status_code == 200, answers.text
    assert answers.json()["saved"] == {"created": 1, "updated": 0, "unchanged": 0}


def test_any_designer_still_answers_an_unattached_form_handed_to_them(client, env):
    """"Not owner-gated" survives for a form that belongs to no workshop, and that is not an
    oversight in the fix. A designer hands a colleague a form and the colleague fills it in;
    ``read_questionnaire`` already shows that colleague their OWN sittings and nobody else's
    (``only_entries_of``), a branch that would be unreachable for the very people it exists for if
    writing were narrowed to the owner. The workshop is what introduces a second party with a stake
    in the answers, so the workshop is what the gate asks about."""
    form = _questionnaire(client, env, f"Unattached form {env['stamp']}", workshop_id=None)

    entry = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "A respondent of the colleague's"},
        headers=_as(env, "outsider"),
    )
    assert entry.status_code == 201, entry.text

    answers = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry.json()['id']}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Four"}]},
        headers=_as(env, "outsider"),
    )
    assert answers.status_code == 200, answers.text
    assert answers.json()["saved"] == {"created": 1, "updated": 0, "unchanged": 0}


def test_the_gate_is_the_grant_and_revoking_it_stops_the_writes_too(client, env):
    """THE CONTROL AND THE POINT OF THE FINDING IN ONE TEST.

    First half: an admin puts the colleague on the workshop and the identical POST that the outsider
    was refused goes through — so the refusal above is the grant talking, not a blanket "no".

    Second half is the sentence the audit asked for: revoking the viewer row takes effect on WRITES
    as it already does on reads. ``PUT /viewers`` replaces the whole set, so sending ``[]`` is how a
    grant is taken away. Before this fix the revoked co-designer kept writing sittings into the
    workshop's annexure for ever, which is the exact scenario the finding described — a colleague
    from last season whose grant is gone and whose browser history still holds the id.
    """
    workshop_id = _workshop(client, env, f"Granted workshop {env['stamp']}")
    form = _questionnaire(client, env, f"Granted form {env['stamp']}", workshop_id=workshop_id)

    before = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Too early"},
        headers=_as(env, "colleague"),
    )
    assert before.status_code == 404, before.text

    grant = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [env["people"]["colleague"].id]},
        headers=_as(env, "admin"),
    )
    assert grant.status_code == 200, grant.text

    allowed = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Recorded by the co-designer"},
        headers=_as(env, "colleague"),
    )
    assert allowed.status_code == 201, allowed.text
    entry_id = allowed.json()["id"]

    answered = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Nine"}]},
        headers=_as(env, "colleague"),
    )
    assert answered.status_code == 200, answered.text

    revoked = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": []},
        headers=_as(env, "admin"),
    )
    assert revoked.status_code == 200, revoked.text

    after = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "After the grant was taken away"},
        headers=_as(env, "colleague"),
    )
    assert after.status_code == 404, after.text
    # ...and the answers half is closed to them too, on the sitting they recorded themselves.
    still = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Ninety"}]},
        headers=_as(env, "colleague"),
    )
    assert still.status_code == 404, still.text

    sittings = _entries(client, env, form["id"])
    assert [e["respondentName"] for e in sittings] == ["Recorded by the co-designer"]
    assert [a["answerText"] for a in sittings[0]["answers"]] == ["Nine"]
