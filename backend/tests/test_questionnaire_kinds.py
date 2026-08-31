"""What kind of questionnaire this is, where it lands in the report, and the three copies of the list.

THE REQUIREMENT, VERBATIM. Owner, 2026-08-30: *"the designer can have multiple questionnaires for
the same workshop as well, they also do market survey interviews, so create that differentiation as
well, so that we can map the questionnaires and the transcripts to the correct stage in the
report."*

Five things are pinned here, and each one is a way the feature could look finished and not be.

1. **SEVERAL QUESTIONNAIRES REALLY DO ATTACH TO ONE WORKSHOP.** This was believed to work before the
   kind existed — ``Questionnaire.designWorkshopId`` is a single nullable column and the far side of
   the relation is a list — but "believed" and "asserted" are different states, and the whole feature
   is pointless if it is false. So it is measured against the database rather than read off the
   schema.

2. **THE VOCABULARY IS ONE LIST, COPIED INTO THREE TREES.** ``questionnaire_kinds.KIND_LABELS`` is
   the original; the web carries a copy in ``frontend/lib/questionnaireForms.ts`` and the handset one
   in ``android/.../ui/questionnaires/QuestionnaireKinds.kt``. Nothing compiles the three against
   each other — the identical situation ``test_role_ladder_parity.py`` was written for — so this file
   reads the other two AS TEXT and holds them to the Python. **When one of these fails, the Python is
   the expectation; find the mirror that lagged.**

3. **EVERY KIND NAMES A REAL STAGE.** ``KIND_STAGE_KEYS`` maps tokens to ``StageSpec.key`` strings.
   A renamed stage would leave the map pointing at nothing, the report would find no title, and the
   annexure would file the questionnaire under a heading naming a stage that does not exist.

4. **THE TOKEN IS VALIDATED AT EVERY DOOR.** Three of them write this column — the JSON create, the
   PATCH, and the multipart upload — and the third has no pydantic model behind it, so it needed a
   hand-written check. An unvalidated token is not a cosmetic problem: it reaches the report, which
   then looks up a stage it will not find.

5. **AN UNSTATED KIND CHANGES NOTHING.** Every questionnaire that existed before this column is
   NULL and there is no backfill, so the annexure must render exactly as it did — no group heading,
   no extra heading level, the questionnaires in creation order. A grouping that "tidied" unstated
   rows into a group of their own would silently re-shape every report already generated.

The parity and mapping halves need nothing but the source tree. The behavioural half needs Postgres,
because what is under test is which rows a request leaves behind; that half skips itself when
``DATABASE_URL`` is not local, exactly as ``test_questionnaire_reuse`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import os
import re
import uuid
from pathlib import Path
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token
from app.services.questionnaire_kinds import (
    KIND_LABELS,
    KIND_STAGE_KEYS,
    KIND_TOKENS,
    NOT_STATED_LABEL,
    coerce_kind,
    is_kind,
    label_for,
    stage_key_for,
)

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

_REPO = Path(__file__).resolve().parents[2]
_TS = _REPO / "frontend" / "lib" / "questionnaireForms.ts"
_KT = (
    _REPO
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "designprototype"
    / "workshop"
    / "ui"
    / "questionnaires"
    / "QuestionnaireKinds.kt"
)


# ================================================================================================
# The vocabulary itself
# ================================================================================================


def test_the_two_kinds_the_owner_asked_for_are_both_here() -> None:
    """The minimum the requirement names, by token.

    Spelled out rather than derived from ``KIND_LABELS`` — a test that read the constant it is
    testing would agree with any list at all, including an empty one.
    """
    assert "WORKSHOP_INTERVIEW" in KIND_TOKENS
    assert "MARKET_SURVEY" in KIND_TOKENS


def test_there_is_no_other_kind_token(monkeypatch: pytest.MonkeyPatch) -> None:
    """No ``OTHER``, and the reason is in the module: this value is a ROUTING INSTRUCTION.

    ``WORKSHOP_KIND`` in the stage registry carries an OTHER and argues for it. That argument does
    not transfer here, because every member of this list has to name a stage the material can land
    in and an OTHER would name none — a questionnaire filed under it would be one the designer had
    answered a question about and that the report still could not place. NULL already says "not
    stated" without pretending a decision was made.
    """
    assert frozenset({"WORKSHOP_INTERVIEW", "MARKET_SURVEY"}) == KIND_TOKENS


def test_every_kind_maps_to_a_stage_and_every_stage_is_real() -> None:
    """The map is complete, and every key in it is a stage this registry actually declares."""
    from app.services.stage_schema import stages

    # ``stages()`` and not ``stage_schema.STAGES``: that name is an empty tuple until
    # ``stage_definitions`` installs the registry, and importing it binds the empty copy — the
    # outage that function's docstring records. A test that imported the constant would pass on an
    # empty set and assert nothing.
    registered = {stage.key for stage in stages()}
    assert set(KIND_STAGE_KEYS) == set(KIND_TOKENS), (
        "every kind must name the stage its material lands in — a kind with no stage is a question "
        "the designer answered that the report cannot act on"
    )
    for kind, key in KIND_STAGE_KEYS.items():
        assert key in registered, (
            f"{kind} maps to {key!r}, which is not a registered stage. A renamed stage leaves this "
            "map pointing at nothing and the annexure files the questionnaire under a heading that "
            "names a stage the rest of the report has stopped using."
        )


def test_the_two_kinds_land_on_the_two_stages_that_were_argued_for() -> None:
    """The specific mapping, by name, because "maps to A stage" is not the requirement.

    Stage 8 ``MARKET_SURVEY_CAPTURE`` is "What the survey actually found: responses from each
    group"; stage 9 ``MARKET_ANALYSIS_DIRECTION`` is "What the survey MEANS: the SWOT…". Recorded
    sittings are responses, not analysis, so a market survey belongs to the CAPTURE stage — filing
    raw verbatim answers under the analysis would put evidence where the conclusions go.

    Stage 6 ``EXISTING_PRODUCTS_BASELINE`` is the artisan baseline, and already the stage that cites
    an interview: its ``artisanBaseline.interviewRef`` exists for exactly that, and the comment above
    it rules out every other candidate by name.
    """
    assert stage_key_for("MARKET_SURVEY") == "MARKET_SURVEY_CAPTURE"
    assert stage_key_for("WORKSHOP_INTERVIEW") == "EXISTING_PRODUCTS_BASELINE"


def test_an_unstated_kind_is_silence_and_not_a_kind() -> None:
    """``None`` and ``""`` are "nobody has said", and must never be mistaken for a decision."""
    assert stage_key_for(None) is None
    assert stage_key_for("") is None
    assert is_kind(None) is False
    assert is_kind("") is False
    assert label_for(None) == NOT_STATED_LABEL
    assert label_for("") == NOT_STATED_LABEL
    assert NOT_STATED_LABEL not in KIND_LABELS.values()


def test_an_unknown_token_prints_itself_rather_than_not_stated() -> None:
    """A row written by a NEWER deployment, read by this one.

    Relabelling a value somebody chose as a value nobody chose is the one wrong answer available
    here — it would tell a designer their market-survey questionnaire is unclassified.
    """
    assert label_for("SOMETHING_NEWER") == "SOMETHING_NEWER"


def test_coerce_accepts_silence_normalises_case_and_refuses_anything_else() -> None:
    """The one validator, and the three answers it can give.

    ``""`` normalising to ``None`` is deliberate and is where this differs from
    ``designWorkshopId``'s ``min_length=1``: an empty workshop id is a malformed foreign key that
    used to reach Prisma and answer 500, while an empty kind is a picker sitting on its blank row,
    which is a real answer. Refusing it would make an untouched dropdown a validation error.
    """
    assert coerce_kind(None) is None
    assert coerce_kind("") is None
    assert coerce_kind("   ") is None
    assert coerce_kind(" market_survey ") == "MARKET_SURVEY"
    with pytest.raises(ValueError) as raised:
        coerce_kind("SOMETHING_ELSE")
    # The refusal must NAME what is allowed. A validation error that says only "invalid" leaves a
    # client author guessing at a closed list they cannot see.
    assert "MARKET_SURVEY" in str(raised.value)
    assert "WORKSHOP_INTERVIEW" in str(raised.value)


# ================================================================================================
# The three copies of one list
# ================================================================================================


def test_the_web_mirror_carries_the_same_tokens_and_the_same_labels() -> None:
    """``frontend/lib/questionnaireForms.ts`` — read as text, held to the Python.

    Reading source is a blunt instrument and deliberately so: the drift being defended against is
    not a logic bug, it is somebody adding a member here and not there, and text is where that
    happens. Same argument, same shape, as ``test_role_ladder_parity.py``.
    """
    source = _TS.read_text(encoding="utf-8")
    block = re.search(
        r"QUESTIONNAIRE_KIND_LABELS:\s*Record<QFormKind,\s*string>\s*=\s*\{(.*?)\}",
        source,
        re.DOTALL,
    )
    assert block, "QUESTIONNAIRE_KIND_LABELS is not in frontend/lib/questionnaireForms.ts"
    pairs = dict(re.findall(r'(\w+):\s*"([^"]*)"', block.group(1)))
    assert pairs == KIND_LABELS, (
        "the web's questionnaire-kind labels have drifted from questionnaire_kinds.KIND_LABELS. "
        "The PYTHON is the expectation — do not edit it to match the mirror."
    )
    # The union type must carry the same members, or a member added to the label map alone is a
    # compile error that reads as a mistake in the map rather than as an unfinished rollout.
    union = re.search(r"export type QFormKind =([^;]+);", source)
    assert union, "QFormKind is not declared in frontend/lib/questionnaireForms.ts"
    assert set(re.findall(r'"([A-Z_]+)"', union.group(1))) == set(KIND_TOKENS)


def test_the_android_mirror_carries_the_same_tokens_and_the_same_labels() -> None:
    """``android/.../ui/questionnaires/QuestionnaireKinds.kt`` — the third copy."""
    source = _KT.read_text(encoding="utf-8")
    block = re.search(r"QUESTIONNAIRE_KIND_LABELS[^=]*=\s*linkedMapOf\((.*?)\)\s", source, re.DOTALL)
    assert block, "QUESTIONNAIRE_KIND_LABELS is not in the Android mirror"
    pairs = dict(re.findall(r'"([A-Z_]+)"\s+to\s+"([^"]*)"', block.group(1)))
    assert pairs == KIND_LABELS, (
        "the handset's questionnaire-kind labels have drifted from "
        "questionnaire_kinds.KIND_LABELS. The PYTHON is the expectation."
    )


def test_both_clients_word_the_unstated_state_identically() -> None:
    """"Kind not stated" is one sentence, and the owner asked for identical wording."""
    assert f'"{NOT_STATED_LABEL}"' in _TS.read_text(encoding="utf-8")
    assert f'"{NOT_STATED_LABEL}"' in _KT.read_text(encoding="utf-8")


def test_the_order_the_pickers_draw_is_the_same_on_both_clients() -> None:
    """Two lists with the same members in different orders are two different controls.

    ``KIND_LABELS`` is a dict and dicts preserve insertion order, the TS constant is an object
    literal, and the Kotlin one is a ``linkedMapOf`` for exactly this reason — a plain ``mapOf``
    would also preserve order today and would not say that the order is load-bearing.
    """
    ts = re.search(
        r"QUESTIONNAIRE_KINDS:\s*QFormKind\[\]\s*=\s*\[(.*?)\]", _TS.read_text(encoding="utf-8"), re.DOTALL
    )
    assert ts, "QUESTIONNAIRE_KINDS is not declared in the web mirror"
    assert re.findall(r'"([A-Z_]+)"', ts.group(1)) == list(KIND_LABELS)

    kt = re.search(
        r"QUESTIONNAIRE_KIND_LABELS[^=]*=\s*linkedMapOf\((.*?)\)\s",
        _KT.read_text(encoding="utf-8"),
        re.DOTALL,
    )
    assert kt
    assert re.findall(r'"([A-Z_]+)"\s+to', kt.group(1)) == list(KIND_LABELS)


# ================================================================================================
# The report: which stage the material lands in
# ================================================================================================


def _item(kind: str, title: str) -> Any:
    """One annexure item with a single answered sitting — the minimum that prints."""
    from app.services.report_questionnaires import (
        QuestionnaireAnswer,
        QuestionnaireItem,
        QuestionnaireSitting,
    )

    return QuestionnaireItem(
        questionnaire_id=f"q-{title}",
        title=title,
        kind=kind,
        question_count=1,
        sittings=(
            QuestionnaireSitting(
                entry_id=f"e-{title}",
                title="Sitting",
                answers=(
                    QuestionnaireAnswer(
                        prompt="How many looms do you own?",
                        answer_text="12",
                        section_code="A",
                        section_title="About the loom",
                    ),
                ),
            ),
        ),
    )


def test_the_annexure_groups_by_stage_in_stage_order() -> None:
    """A market survey and a workshop interview must not print under one undifferentiated heading.

    Stage 6 before stage 8, whatever order they were created in, so the annexure reads in the order
    the workshop happened — which is the order the body of the report is already in.
    """
    from app.services.report_questionnaires import grouped_by_stage

    groups = grouped_by_stage(
        [_item("MARKET_SURVEY", "Shopkeepers"), _item("WORKSHOP_INTERVIEW", "Artisans")]
    )
    headings = [heading for heading, _items in groups]
    assert len(groups) == 2
    assert "Workshop interview" in headings[0] and "stage 6" in headings[0]
    assert "Market survey" in headings[1] and "stage 8" in headings[1]
    assert [item.title for item in groups[0][1]] == ["Artisans"]
    assert [item.title for item in groups[1][1]] == ["Shopkeepers"]


def test_unstated_questionnaires_keep_the_old_flat_shape() -> None:
    """THE COMPATIBILITY CLAIM, asserted rather than hoped for.

    Every questionnaire that predates the ``kind`` column is NULL and there is no backfill, so a
    report built from those rows must render as it always did: ONE group, with an EMPTY heading,
    which the renderer draws as no heading at all and no extra heading level. A grouping that filed
    unstated rows under a "Kind not stated" heading would silently re-shape every report already
    generated for every workshop in the repository.
    """
    from app.services.report_questionnaires import grouped_by_stage

    groups = grouped_by_stage([_item("", "An older form"), _item("", "Another older form")])
    assert len(groups) == 1
    assert groups[0][0] == ""
    assert [item.title for item in groups[0][1]] == ["An older form", "Another older form"]


def test_stated_kinds_sort_before_the_unstated_remainder() -> None:
    """The filed material first, the unfiled remainder last — never interleaved."""
    from app.services.report_questionnaires import grouped_by_stage

    groups = grouped_by_stage(
        [_item("", "Unfiled"), _item("MARKET_SURVEY", "Shopkeepers"), _item("", "Also unfiled")]
    )
    assert [heading == "" for heading, _ in groups] == [False, True]
    assert [item.title for item in groups[-1][1]] == ["Unfiled", "Also unfiled"]


def test_an_unknown_kind_groups_under_itself_rather_than_vanishing() -> None:
    """A token from a newer deployment still prints, still labelled, still after the real stages."""
    from app.services.report_questionnaires import grouped_by_stage

    groups = grouped_by_stage([_item("SOMETHING_NEWER", "From the future"), _item("", "Unfiled")])
    assert groups[0][0] == "SOMETHING_NEWER"
    assert groups[1][0] == ""


def test_the_annexure_index_names_the_kind_of_every_row() -> None:
    """The index table is the one place a reader sees every questionnaire at once."""
    from app.services.report_questionnaires import questionnaire_index_block

    block = questionnaire_index_block(
        (_item("MARKET_SURVEY", "Shopkeepers"), _item("", "An older form"))
    )
    assert [column.header for column in block.columns][1] == "Kind"
    cells = ["".join(run.text for run in cell) for row in block.rows for cell in row]
    assert "Market survey" in cells
    # The unstated row says so rather than leaving the cell blank: a blank cell in a printed table
    # reads as data that failed to load rather than as a question nobody answered.
    assert NOT_STATED_LABEL in cells


# ================================================================================================
# The database and the API
# ================================================================================================

pytestmark = pytest.mark.anyio


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    """An admin, a designer, a professor and a live TestClient.

    THE ROWS ARE MADE HERE AND THE CONNECTION IS OPENED EXACTLY ONCE, which is the convention every
    database-backed suite in this directory follows and is not optional. The Prisma client is a
    MODULE-LEVEL singleton shared with the running app: the ``TestClient`` context manager runs the
    app's lifespan, which connects it, so a test that calls ``db.connect()`` of its own raises
    ``AlreadyConnectedError``, and one that calls ``db.disconnect()`` tears the connection out from
    under the client for every test after it. Beyond that, a query issued from a test's own event
    loop against an engine bound to the client's is the kind of cross-loop use that fails
    intermittently rather than honestly.

    So: rows before the client, assertions through HTTP. Where a claim really is about rows rather
    than responses — "the failed create left nothing behind" — it is measured through the API's own
    list, which is the surface a designer would look at anyway.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in (
            ("admin", "ADMIN", "Kind Admin"),
            ("designer", "DESIGNER", "Kind Designer"),
            ("professor", "PROFESSOR", "Kind Professor"),
        ):
            people[slug] = await db.user.create(
                data={"email": f"qkind-{slug}-{stamp}@example.org", "name": name, "role": role}
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp}


def _as(env: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_several_questionnaires_attach_to_one_workshop(env) -> None:
    """THE FIRST HALF OF THE OWNER'S SENTENCE, measured rather than read off the schema.

    "the designer can have multiple questionnaires for the same workshop as well". The schema says
    this works — one nullable column, a list on the far side — but the whole feature rests on it, and
    a belief is not an assertion. Both rows must exist, both must be attached, and the report
    annexure must SEE both: a limit that let two rows exist while the annexure printed one would look
    identical from the API and lose half the fieldwork in the document.
    """
    client = env["client"]
    workshop = client.post(
        "/api/design-workshops",
        json={"title": f"Two questionnaires {env['stamp']}"},
        headers=_as(env, "admin"),
    )
    assert workshop.status_code == 201, workshop.text
    workshop_id = workshop.json()["id"]

    made = []
    for title, kind in (("The artisans", "WORKSHOP_INTERVIEW"), ("The shopkeepers", "MARKET_SURVEY")):
        response = client.post(
            "/api/questionnaires",
            json={"title": title, "designWorkshopId": workshop_id, "kind": kind},
            headers=_as(env, "admin"),
        )
        assert response.status_code == 201, response.text
        body = response.json()
        assert body["designWorkshopId"] == workshop_id
        assert body["kind"] == kind
        made.append(body["id"])

    # BOTH ARE STILL THERE AFTERWARDS, which is the half a create response cannot answer: a second
    # attachment that had silently displaced the first would return 201 for both and leave one row.
    # Read back through the list's own ``designWorkshopId`` filter — the same query the report
    # annexure's ``report_items`` runs, and the one a designer would look at.
    listed = client.get(
        f"/api/questionnaires?designWorkshopId={workshop_id}&pageSize=50", headers=_as(env, "admin")
    )
    assert listed.status_code == 200, listed.text
    rows = {row["id"]: row for row in listed.json()["items"]}
    assert set(made) <= set(rows), "one of the two questionnaires is no longer attached"
    assert {rows[q]["kind"] for q in made} == {"WORKSHOP_INTERVIEW", "MARKET_SURVEY"}
    assert {rows[q]["kindLabel"] for q in made} == {"Workshop interview", "Market survey"}


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_a_bad_kind_is_a_422_at_every_door(env) -> None:
    """THREE DOORS WRITE THIS COLUMN and all three must refuse an unknown token.

    The JSON create and the PATCH are covered by a pydantic validator; the multipart UPLOAD has no
    model behind it and needed a hand-written check, which is exactly the door most likely to be
    forgotten. An unvalidated token is not cosmetic: it reaches the report, which then looks up a
    stage that does not exist.
    """
    client = env["client"]
    created = client.post(
        "/api/questionnaires", json={"title": "Kind doors"}, headers=_as(env, "designer")
    )
    assert created.status_code == 201, created.text
    questionnaire_id = created.json()["id"]

    assert (
        client.post(
            "/api/questionnaires",
            json={"title": "Nope", "kind": "NOT_A_KIND"},
            headers=_as(env, "designer"),
        ).status_code
        == 422
    )
    assert (
        client.patch(
            f"/api/questionnaires/{questionnaire_id}",
            json={"kind": "NOT_A_KIND"},
            headers=_as(env, "designer"),
        ).status_code
        == 422
    )
    upload = client.post(
        "/api/questionnaires/upload",
        files={"file": ("form.xlsx", b"not a workbook", "application/octet-stream")},
        data={"kind": "NOT_A_KIND"},
        headers=_as(env, "designer"),
    )
    assert upload.status_code == 422, upload.text


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_the_kind_can_be_set_and_cleared_back_to_not_stated(env) -> None:
    """"Not stated" has to be reachable AGAIN, or the picker's blank row is a one-way door.

    ``clean_data`` drops an explicit null unless the column is named in the route's
    ``_QUESTIONNAIRE_CLEARABLE_COLUMNS``, so without that name this PATCH would answer 200 and change
    nothing — the designer would un-set a kind and be shown the kind they thought they had removed,
    while the report went on filing the form under a stage they no longer claim.
    """
    client = env["client"]
    created = client.post(
        "/api/questionnaires", json={"title": "Clearable kind"}, headers=_as(env, "designer")
    )
    questionnaire_id = created.json()["id"]
    assert created.json()["kind"] is None
    assert created.json()["kindLabel"] == NOT_STATED_LABEL

    set_it = client.patch(
        f"/api/questionnaires/{questionnaire_id}",
        json={"kind": "MARKET_SURVEY"},
        headers=_as(env, "designer"),
    )
    assert set_it.status_code == 200, set_it.text
    assert set_it.json()["kind"] == "MARKET_SURVEY"
    assert set_it.json()["kindLabel"] == "Market survey"

    cleared = client.patch(
        f"/api/questionnaires/{questionnaire_id}", json={"kind": None}, headers=_as(env, "designer")
    )
    assert cleared.status_code == 200, cleared.text
    assert cleared.json()["kind"] is None

    # And an OMITTED key leaves it alone, which is the other half of the same convention.
    client.patch(
        f"/api/questionnaires/{questionnaire_id}",
        json={"kind": "WORKSHOP_INTERVIEW"},
        headers=_as(env, "designer"),
    )
    untouched = client.patch(
        f"/api/questionnaires/{questionnaire_id}",
        json={"title": "Clearable kind, renamed"},
        headers=_as(env, "designer"),
    )
    assert untouched.json()["kind"] == "WORKSHOP_INTERVIEW"


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_a_reused_questionnaire_keeps_the_kind_and_not_the_workshop(env) -> None:
    """The copy inherits the INSTRUMENT'S nature and not its filing.

    A market survey lifted for another cluster is still a market survey, so it should land in the new
    workshop's market-survey stage without the designer having to say so again. The WORKSHOP is the
    one thing a copy must not inherit — that is ``QuestionnaireReuse``'s own argument — so the two
    defaults point in opposite directions on purpose, and this pins both at once.
    """
    client = env["client"]
    workshop = client.post(
        "/api/design-workshops",
        json={"title": f"Reuse keeps kind {env['stamp']}"},
        headers=_as(env, "admin"),
    )
    workshop_id = workshop.json()["id"]
    source = client.post(
        "/api/questionnaires",
        json={"title": "Mandi survey", "designWorkshopId": workshop_id, "kind": "MARKET_SURVEY"},
        headers=_as(env, "admin"),
    )
    assert source.status_code == 201, source.text

    copy = client.post(
        f"/api/questionnaires/{source.json()['id']}/reuse", json={}, headers=_as(env, "admin")
    )
    assert copy.status_code == 201, copy.text
    # ``/reuse`` answers ``{questionnaire, report, sourceQuestionnaireId}`` rather than the bare row —
    # the copy plus the change report the dialog renders. Reading the top level for a column here is
    # a KeyError, which is how this assertion first failed.
    made = copy.json()["questionnaire"]
    assert made["kind"] == "MARKET_SURVEY"
    assert made["kindLabel"] == "Market survey"
    assert made["designWorkshopId"] is None


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_the_create_is_one_transaction(env) -> None:
    """D2: a create that fails part way must leave NOTHING, not an orphan with a partial tree.

    The route wrote the row, then each section, then each question on separate awaits. A failure
    mid-loop committed everything before it while the client saw a 500 — the designer presses the
    button again and owns a truncated questionnaire beside a whole one, with nothing on any screen
    able to say which is short.

    THE FAILURE IS INJECTED AT THE LAST WRITE, which is the only place that distinguishes the two
    implementations: a transaction rolls the ``Questionnaire`` row back with it, and the old loop
    did not. Counting rows before and after is what makes this a test of the transaction rather than
    of the error handling.
    """
    from unittest.mock import patch as mock_patch

    client = env["client"]
    title = f"Transactional create {uuid.uuid4().hex[:8]}"

    def _rows_titled(name: str) -> list[dict[str, Any]]:
        """The questionnaires this designer can see under ``name``, through the API's own list.

        THROUGH HTTP AND NOT THROUGH ``db``: the Prisma client belongs to the TestClient's event
        loop (see the ``env`` fixture), and this is also the surface the designer would look at —
        "did my questionnaire get created?" is answered by the list, so measuring the orphan there
        measures the thing that actually goes wrong. ``activeOnly=false`` so a row that somehow
        arrived deactivated still counts as the orphan it would be.
        """
        found = client.get(
            f"/api/questionnaires?search={name}&activeOnly=false&pageSize=50",
            headers=_as(env, "designer"),
        )
        assert found.status_code == 200, found.text
        return [row for row in found.json()["items"] if row["title"] == name]

    assert _rows_titled(title) == []

    payload = {
        "title": title,
        "sections": [
            {"title": "About the loom", "questions": [{"prompt": "How many looms do you own?"}]},
            {"title": "About the yarn", "questions": [{"prompt": "Where is the yarn bought?"}]},
        ],
    }

    async def _explode(*_args: Any, **_kwargs: Any) -> None:
        raise RuntimeError("the connection went away mid-write")

    with mock_patch(
        "prisma.actions.QuestionnaireFormQuestionActions.create_many", side_effect=_explode
    ):
        response = client.post("/api/questionnaires", json=payload, headers=_as(env, "designer"))
    assert response.status_code >= 500

    assert _rows_titled(title) == [], (
        "the failed create left a Questionnaire row behind. The row, its sections and its questions "
        "must be one transaction — a half-written instrument is worse than none, because it is "
        "indistinguishable from a whole one."
    )


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_a_create_with_sections_still_writes_the_whole_tree(env) -> None:
    """The other half of the transaction change: the happy path must be unchanged.

    Batching the writes is what keeps the transaction short, and a batch is exactly where a section's
    questions can end up attached to the wrong section — the read-back is keyed on
    ``@@unique([questionnaireId, code])``, so two sections whose codes collided would silently merge.
    Two sections with DIFFERENT questions is the fixture that would catch it.
    """
    client = env["client"]
    response = client.post(
        "/api/questionnaires",
        json={
            "title": f"Whole tree {uuid.uuid4().hex[:6]}",
            "sections": [
                {
                    "title": "About the loom",
                    "questions": [{"prompt": "How many looms?"}, {"prompt": "How old is the oldest?"}],
                },
                {"title": "About the yarn", "questions": [{"prompt": "Where is the yarn bought?"}]},
            ],
        },
        headers=_as(env, "designer"),
    )
    assert response.status_code == 201, response.text
    body = response.json()
    sections = {section["title"]: section for section in body["sections"]}
    assert set(sections) == {"About the loom", "About the yarn"}
    assert [q["prompt"] for q in sections["About the loom"]["questions"]] == [
        "How many looms?",
        "How old is the oldest?",
    ]
    assert [q["prompt"] for q in sections["About the yarn"]["questions"]] == [
        "Where is the yarn bought?"
    ]
    assert body["questionCount"] == 3


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_two_sections_with_one_title_do_not_collide(env) -> None:
    """The section codes are derived before the transaction, and derivation must stay unique.

    ``derive_section_code`` mutates the ``taken`` set it is given, which is what makes the second
    "Materials" land on ``MATERIALS_2``. Losing that would be a unique-constraint violation inside
    the transaction — which now correctly rolls the whole create back, i.e. a designer would lose the
    create rather than getting a merged section. Worth pinning where the derivation happens.
    """
    client = env["client"]
    response = client.post(
        "/api/questionnaires",
        json={
            "title": f"Same titles {uuid.uuid4().hex[:6]}",
            "sections": [
                {"title": "Materials", "questions": [{"prompt": "Which fibre?"}]},
                {"title": "Materials", "questions": [{"prompt": "Bought where?"}]},
            ],
        },
        headers=_as(env, "designer"),
    )
    assert response.status_code == 201, response.text
    codes = [section["code"] for section in response.json()["sections"]]
    assert len(set(codes)) == 2, codes


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_the_refusal_names_the_roles_and_who_to_ask(env) -> None:
    """D3: a PROFESSOR is refused, and must not be told they lack a rank they exceed.

    ``can_run_design_workshops`` is the SET ``{DESIGNER, ADMIN, MASTER_ADMIN}`` and not a floor over
    ``ROLE_RANK``, so a PROFESSOR (40) is outside it while outranking a DESIGNER (35). The old
    message — "requires Designer access or above" — left them with no move available: they cannot be
    promoted to a tier they already outrank.
    """
    client = env["client"]
    response = client.post(
        "/api/questionnaires", json={"title": "Refused"}, headers=_as(env, "professor")
    )
    assert response.status_code == 403
    detail = response.json()["detail"]
    assert "Designer" in detail and "Admin" in detail and "Master Admin" in detail
    assert "administrator" in detail.lower(), "the refusal must name who can widen it"
    assert "or above" not in detail, (
        "the gate is a SET, not a rank floor — a message promising 'or above' is false for every "
        "role above DESIGNER that is outside the set"
    )
