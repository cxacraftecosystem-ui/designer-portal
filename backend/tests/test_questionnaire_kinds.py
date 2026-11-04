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



def test_the_two_kinds_the_owner_asked_for_are_both_here() -> None:
    assert "WORKSHOP_INTERVIEW" in KIND_TOKENS
    assert "MARKET_SURVEY" in KIND_TOKENS


def test_there_is_no_other_kind_token(monkeypatch: pytest.MonkeyPatch) -> None:
    assert frozenset({"WORKSHOP_INTERVIEW", "MARKET_SURVEY"}) == KIND_TOKENS


def test_every_kind_maps_to_a_stage_and_every_stage_is_real() -> None:
    from app.services.stage_schema import stages

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
    assert stage_key_for("MARKET_SURVEY") == "MARKET_SURVEY_CAPTURE"
    assert stage_key_for("WORKSHOP_INTERVIEW") == "EXISTING_PRODUCTS_BASELINE"


def test_an_unstated_kind_is_silence_and_not_a_kind() -> None:
    assert stage_key_for(None) is None
    assert stage_key_for("") is None
    assert is_kind(None) is False
    assert is_kind("") is False
    assert label_for(None) == NOT_STATED_LABEL
    assert label_for("") == NOT_STATED_LABEL
    assert NOT_STATED_LABEL not in KIND_LABELS.values()


def test_an_unknown_token_prints_itself_rather_than_not_stated() -> None:
    assert label_for("SOMETHING_NEWER") == "SOMETHING_NEWER"


def test_coerce_accepts_silence_normalises_case_and_refuses_anything_else() -> None:
    assert coerce_kind(None) is None
    assert coerce_kind("") is None
    assert coerce_kind("   ") is None
    assert coerce_kind(" market_survey ") == "MARKET_SURVEY"
    with pytest.raises(ValueError) as raised:
        coerce_kind("SOMETHING_ELSE")
    assert "MARKET_SURVEY" in str(raised.value)
    assert "WORKSHOP_INTERVIEW" in str(raised.value)



def test_the_web_mirror_carries_the_same_tokens_and_the_same_labels() -> None:
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
    union = re.search(r"export type QFormKind =([^;]+);", source)
    assert union, "QFormKind is not declared in frontend/lib/questionnaireForms.ts"
    assert set(re.findall(r'"([A-Z_]+)"', union.group(1))) == set(KIND_TOKENS)


def test_the_android_mirror_carries_the_same_tokens_and_the_same_labels() -> None:
    source = _KT.read_text(encoding="utf-8")
    block = re.search(r"QUESTIONNAIRE_KIND_LABELS[^=]*=\s*linkedMapOf\((.*?)\)\s", source, re.DOTALL)
    assert block, "QUESTIONNAIRE_KIND_LABELS is not in the Android mirror"
    pairs = dict(re.findall(r'"([A-Z_]+)"\s+to\s+"([^"]*)"', block.group(1)))
    assert pairs == KIND_LABELS, (
        "the handset's questionnaire-kind labels have drifted from "
        "questionnaire_kinds.KIND_LABELS. The PYTHON is the expectation."
    )


def test_both_clients_word_the_unstated_state_identically() -> None:
    assert f'"{NOT_STATED_LABEL}"' in _TS.read_text(encoding="utf-8")
    assert f'"{NOT_STATED_LABEL}"' in _KT.read_text(encoding="utf-8")


def test_the_order_the_pickers_draw_is_the_same_on_both_clients() -> None:
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



def _item(kind: str, title: str) -> Any:
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
    from app.services.report_questionnaires import grouped_by_stage

    groups = grouped_by_stage([_item("", "An older form"), _item("", "Another older form")])
    assert len(groups) == 1
    assert groups[0][0] == ""
    assert [item.title for item in groups[0][1]] == ["An older form", "Another older form"]


def test_stated_kinds_sort_before_the_unstated_remainder() -> None:
    from app.services.report_questionnaires import grouped_by_stage

    groups = grouped_by_stage(
        [_item("", "Unfiled"), _item("MARKET_SURVEY", "Shopkeepers"), _item("", "Also unfiled")]
    )
    assert [heading == "" for heading, _ in groups] == [False, True]
    assert [item.title for item in groups[-1][1]] == ["Unfiled", "Also unfiled"]


def test_an_unknown_kind_groups_under_itself_rather_than_vanishing() -> None:
    from app.services.report_questionnaires import grouped_by_stage

    groups = grouped_by_stage([_item("SOMETHING_NEWER", "From the future"), _item("", "Unfiled")])
    assert groups[0][0] == "SOMETHING_NEWER"
    assert groups[1][0] == ""


def test_the_annexure_index_names_the_kind_of_every_row() -> None:
    from app.services.report_questionnaires import questionnaire_index_block

    block = questionnaire_index_block(
        (_item("MARKET_SURVEY", "Shopkeepers"), _item("", "An older form"))
    )
    assert [column.header for column in block.columns][1] == "Kind"
    cells = ["".join(run.text for run in cell) for row in block.rows for cell in row]
    assert "Market survey" in cells
    assert NOT_STATED_LABEL in cells


pytestmark = pytest.mark.anyio


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
    made = copy.json()["questionnaire"]
    assert made["kind"] == "MARKET_SURVEY"
    assert made["kindLabel"] == "Market survey"
    assert made["designWorkshopId"] is None


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
async def test_the_create_is_one_transaction(env) -> None:
    from unittest.mock import patch as mock_patch

    client = env["client"]
    title = f"Transactional create {uuid.uuid4().hex[:8]}"

    def _rows_titled(name: str) -> list[dict[str, Any]]:
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
