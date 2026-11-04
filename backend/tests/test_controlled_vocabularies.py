import json
import os
import pathlib

import pytest

import app.services.stage_definitions  # noqa: F401
from app.services.report_builder import format_value
from app.services.stage_schema import (
    ENUMS,
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    FieldType,
    coerce_value,
    registry_to_dict,
    registry_version,
    stage_by_number,
    validate_entry,
    validate_registry,
)

ANDROID_ASSET = (
    pathlib.Path(__file__).resolve().parents[2]
    / "android" / "app" / "src" / "main" / "assets" / "design-workshop-schema.json"
)


def _entity(stage_number: int, entity_key: str):
    entity = stage_by_number(stage_number).entity(entity_key)
    assert entity is not None, f"stage {stage_number} has no {entity_key!r} entity"
    return entity



def test_the_registry_is_still_sound():
    problems = validate_registry()
    assert problems == [], "\n".join(problems)


def test_a_tool_has_a_controlled_family_beside_its_free_text_type():
    tool = _entity(5, "tool")

    family = tool.field("toolFamily")
    assert family is not None, "stage 5's tool entity has no controlled tool list"
    assert family.type is FieldType.ENUM
    assert family.enum == "TOOL_TYPE"

    text = tool.field("toolType")
    assert text is not None and text.type is FieldType.TEXT
    assert not text.deprecated


def test_the_tool_families_are_the_ones_this_repository_actually_documents():
    assert set(ENUMS["TOOL_TYPE"]) == {
        "HAND_TOOL", "LOOM", "LOOM_ACCESSORY", "FRAME", "VESSEL", "KILN", "MOULD", "OTHER",
    }


def test_a_survey_place_is_typed_from_the_vocabulary_the_registry_already_has():
    place = _entity(7, "surveyPlace")

    channel = place.field("placeChannel")
    assert channel is not None, "surveyPlace has no controlled place vocabulary"
    assert channel.type is FieldType.ENUM
    assert channel.enum == "MARKET_CHANNEL"

    text = place.field("placeType")
    assert text is not None and text.type is FieldType.TEXT
    assert not text.deprecated


def test_an_iteration_names_every_problem_area_it_touched_not_just_one():
    iteration = _entity(14, "prototypeIteration")

    areas = iteration.field("problemAreas")
    assert areas is not None, "stage 14 has no controlled problem vocabulary"
    assert areas.type is FieldType.MULTI_ENUM
    assert areas.enum == "PROBLEM_TYPE"
    assert set(ENUMS["PROBLEM_TYPE"]) == {"MATERIAL", "PROCESS", "DESIGN"}

    text = iteration.field("problemType")
    assert text is not None and text.type is FieldType.TEXT
    assert not text.deprecated



@pytest.mark.parametrize(
    ("stage_number", "entity_key", "text_key", "enum_key", "recorded"),
    [
        (5, "tool", "toolType", "toolFamily", "Vessel over a firewood hearth"),
        (5, "tool", "toolType", "toolFamily", "Hand-turned warping frame"),
        (7, "surveyPlace", "placeType", "placeChannel",
         "State handloom emporium and adjoining retail row"),
        (14, "prototypeIteration", "problemType", "problemAreas",
         "Design and material — motif density and weft composition"),
    ],
)
def test_an_existing_free_text_answer_survives_the_new_enum_beside_it(
    stage_number, entity_key, text_key, enum_key, recorded
):
    entity = _entity(stage_number, entity_key)
    text_spec = entity.field(text_key)
    enum_spec = entity.field(enum_key)

    cleaned, errors = validate_entry(entity, {text_key: recorded}, enforce_required=False)
    assert errors.get(text_key) is None, f"a stored answer was rejected: {errors}"
    assert cleaned[text_key] == recorded
    assert format_value(text_spec, recorded) == recorded

    value, error = coerce_value(enum_spec, recorded)
    assert value is None and error, "the sentence would have been accepted as a token"


def test_a_token_the_registry_has_never_seen_still_prints_rather_than_vanishing():
    iteration = _entity(14, "prototypeIteration")
    areas = iteration.field("problemAreas")
    assert format_value(areas, ["MATERIAL", "COSTING"]) == "Material, COSTING"



def test_the_tool_picker_reaches_the_whole_documented_catalogue():
    tool_ref = _entity(5, "tool").field("toolRef")
    assert tool_ref.ref_scope == REF_SCOPE_ALL


def test_the_process_picker_stays_inside_the_workshop():
    process_ref = _entity(5, "processStep").field("processRef")
    assert process_ref.ref_scope == REF_SCOPE_WORKSHOP



def test_the_bundled_android_asset_matches_the_registry_it_was_dumped_from():
    assert ANDROID_ASSET.exists(), f"the bundled registry is missing: {ANDROID_ASSET}"
    bundled = json.loads(ANDROID_ASSET.read_text(encoding="utf-8"))
    assert bundled["version"] == registry_version()


def test_the_bundled_android_asset_is_the_registry_it_claims_to_be():
    assert ANDROID_ASSET.exists(), f"the bundled registry is missing: {ANDROID_ASSET}"
    bundled = json.loads(ANDROID_ASSET.read_text(encoding="utf-8"))
    live = registry_to_dict()

    assert sorted(bundled) == sorted(live), "the asset's top-level shape is not the registry's"
    assert bundled["enums"] == live["enums"], (
        "the bundled enums differ from the registry's — a controlled list a phone with no signal "
        "offers is not the list the server validates against"
    )
    assert [s["key"] for s in bundled["stages"]] == [s["key"] for s in live["stages"]], (
        "the asset and the registry do not even carry the same stages, in the same order"
    )
    stale = [
        stage["key"]
        for stage, shipped in zip(live["stages"], bundled["stages"])
        if stage != shipped
    ]
    assert stale == [], f"the bundled asset is stale for these stages: {stale}"
    assert bundled == live


def test_a_new_tool_token_moves_the_asset_and_not_the_version():
    probe = "ZZ_PROBE_NOT_A_REAL_TOOL"
    assert probe not in ENUMS["TOOL_TYPE"]

    before_version = registry_version()
    before_dump = registry_to_dict()
    ENUMS["TOOL_TYPE"][probe] = "Probe token"
    try:
        assert registry_version() == before_version, (
            "the digest moved for a vocabulary edit — if this is deliberate, the note in "
            "registry_version about not invalidating every cached draft needs rewriting"
        )
        assert registry_to_dict() != before_dump, (
            "an enum gained a token and the dumped registry did not change, which would mean the "
            "asset never carries the vocabularies at all"
        )
    finally:
        ENUMS["TOOL_TYPE"].pop(probe, None)

    assert registry_version() == before_version
    assert registry_to_dict() == before_dump


def _local_database_url() -> str:
    try:
        from app.core.config import get_settings

        return str(get_settings().database_url or "")
    except Exception:      # noqa: BLE001 - no settings at all is simply "not local"
        return os.environ.get("DATABASE_URL", "")


_LOCAL_DB = any(h in _local_database_url() for h in ("localhost", "127.0.0.1"))

needs_db = pytest.mark.skipif(
    not _LOCAL_DB, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)

LEGACY_TOOL_TYPE = "Vessel over a firewood hearth"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def client():
    import uuid

    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import create_access_token, hash_password
    from app.main import app

    await db.connect()
    try:
        user = await db.user.create(data={
            "email": f"vocab-test-{uuid.uuid4().hex[:8]}@example.org",
            "name": "Vocabulary Test",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
    finally:
        await db.disconnect()

    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield c


@pytest.mark.anyio
@needs_db
async def test_a_tool_row_written_before_the_enum_existed_still_saves_and_still_prints(client):
    created = client.post("/api/design-workshops", json={"title": "Vocabulary round trip"})
    assert created.status_code == 201, created.text
    workshop_id = created.json()["id"]

    saved = client.put(
        f"/api/design-workshops/{workshop_id}/stages/TRADITIONAL_PROCESS_BASELINE",
        json={
            "entries": [{
                "entityKey": "tool",
                "ordinal": 0,
                "data": {
                    "_clientKey": "legacy-tool",
                    "name": "Dyeing vessel",
                    "toolType": LEGACY_TOOL_TYPE,
                },
            }],
            "submit": True,
        },
    )
    assert saved.status_code == 200, saved.text
    assert saved.json()["errors"] == {}, "a stage written last season was refused at submit"

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/stages/TRADITIONAL_PROCESS_BASELINE"
    ).json()["collections"]["tool"]
    assert [r["toolType"] for r in rows] == [LEGACY_TOOL_TYPE], "the stored sentence changed"

    preview = client.get(f"/api/design-workshops/{workshop_id}/report/preview")
    assert preview.status_code == 200, preview.text
    assert LEGACY_TOOL_TYPE in json.dumps(preview.json(), ensure_ascii=False), \
        "the answer was accepted and stored, then dropped out of the report"


@pytest.mark.anyio
@needs_db
async def test_the_same_sentence_offered_to_the_enum_is_refused_without_taking_the_row_with_it(
    client
):
    created = client.post("/api/design-workshops", json={"title": "Vocabulary counterfactual"})
    workshop_id = created.json()["id"]

    saved = client.put(
        f"/api/design-workshops/{workshop_id}/stages/TRADITIONAL_PROCESS_BASELINE",
        json={
            "entries": [{
                "entityKey": "tool",
                "ordinal": 0,
                "data": {
                    "_clientKey": "counterfactual",
                    "name": "Dyeing vessel",
                    "toolType": LEGACY_TOOL_TYPE,
                    "toolFamily": LEGACY_TOOL_TYPE,
                },
            }],
        },
    )
    assert saved.status_code == 200, saved.text
    assert "toolFamily" in saved.json()["errors"].get("tool[0]", {})

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/stages/TRADITIONAL_PROCESS_BASELINE"
    ).json()["collections"]["tool"]
    assert rows[0]["toolType"] == LEGACY_TOOL_TYPE
    assert not rows[0].get("toolFamily")
