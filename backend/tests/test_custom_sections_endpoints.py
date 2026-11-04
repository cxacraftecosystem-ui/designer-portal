import os
import uuid
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.custom_sections import CUSTOM_ENTITY_KEY, V1_FIELD_TYPES

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

needs_db = pytest.mark.skipif(
    not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)

STAGE = "CLUSTER_CRAFT_BACKGROUND"

EVERY_TYPE: tuple[dict[str, Any], ...] = (
    {"key": "fText", "label": "Text", "type": "TEXT", "help": "a note", "maxLength": 40},
    {"key": "fLong", "label": "Long text", "type": "LONG_TEXT"},
    {"key": "fInt", "label": "Int", "type": "INT", "unit": "looms", "minValue": 0, "maxValue": 99},
    {"key": "fDec", "label": "Decimal", "type": "DECIMAL", "minValue": 0.5, "maxValue": 9.5},
    {"key": "fMoney", "label": "Money", "type": "MONEY", "minValue": 0},
    {"key": "fPct", "label": "Percent", "type": "PERCENT", "minValue": 0, "maxValue": 100},
    {"key": "fDate", "label": "Date", "type": "DATE"},
    {"key": "fTime", "label": "Time", "type": "TIME"},
    {"key": "fBool", "label": "Bool", "type": "BOOL", "tier": "BASIC", "required": True},
    {"key": "fEnum", "label": "Enum", "type": "ENUM",
     "options": [{"value": "indigo", "label": "Indigo"}, {"value": "madder", "label": "Madder"}]},
    {"key": "fMulti", "label": "Multi enum", "type": "MULTI_ENUM",
     "options": [{"value": "a", "label": "A"}, {"value": "b", "label": "B"}]},
    {"key": "fTags", "label": "Tags", "type": "TAGS"},
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    if not _LOCAL:
        pytest.skip("needs a local database")
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        designer = await db.user.create(data={
            "email": f"customsections-{stamp}@example.org",
            "name": "Custom Sections Designer",
            "role": "DESIGNER",
            "passwordHash": hash_password("unused"),
        })
        admin = await db.user.create(data={
            "email": f"customsections-admin-{stamp}@example.org",
            "name": "Custom Sections Admin",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        await db.designerroster.create(data={
            "email": f"customsections-{stamp}@example.org",
            "fullName": designer.name,
            "institution": "Directorate of Handicrafts",
            "isActive": True,
            "addedById": admin.id,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        client.headers.update(
            {"Authorization": f"Bearer {create_access_token(subject=designer.id)}"}
        )
        yield {"client": client, "designer": designer, "admin": admin, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _as_admin(world) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


@pytest.fixture
def workshop(client, world):
    created = client.post(
        "/api/design-workshops",
        json={"title": f"Custom sections {uuid.uuid4().hex[:8]}"},
        headers=_as_admin(world),
    )
    assert created.status_code == 201, created.text
    workshop_id = created.json()["id"]
    granted = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["designer"].id]},
        headers=_as_admin(world),
    )
    assert granted.status_code == 200, granted.text
    return workshop_id


def put_definition(
    client: Any,
    workshop: str,
    sections: list[dict[str, Any]],
    customSchemaVersion: str | None = None,
) -> Any:
    body: dict[str, Any] = {"sections": sections}
    if customSchemaVersion is not None:
        body["customSchemaVersion"] = customSchemaVersion
    return client.put(f"/api/design-workshops/{workshop}/custom-sections", json=body)


def one_section(fields: tuple[dict[str, Any], ...] | list[dict[str, Any]], **over: Any) -> dict:
    section = {"key": "dyenotes", "title": "Dye notes", "stageKey": STAGE, "fields": list(fields)}
    section.update(over)
    return section


async def stored_fields(workshop: str) -> dict[str, Any]:
    from prisma import Prisma

    own = Prisma()
    await own.connect()
    try:
        sections = await own.dwcustomsection.find_many(where={"designWorkshopId": workshop})
        rows = await own.dwcustomfield.find_many(
            where={"sectionId": {"in": [s.id for s in sections]}}
        )
        return {row.key: row for row in rows}
    finally:
        await own.disconnect()



@needs_db
@pytest.mark.anyio
async def test_the_exact_body_that_answered_500_for_the_life_of_this_endpoint_now_saves(
    client, workshop
):
    saved = put_definition(client, workshop, [one_section([
        {"key": "dyesrc", "label": "Dye source", "type": "TEXT"}
    ])])
    assert saved.status_code == 200, saved.text
    body = saved.json()
    assert body["created"] == 1
    assert body["customSchemaVersion"] != ""

    rows = await stored_fields(workshop)
    assert set(rows) == {"dyesrc"}
    assert rows["dyesrc"].label == "Dye source"
    assert rows["dyesrc"].type == "TEXT"
    assert rows["dyesrc"].options is None


@needs_db
@pytest.mark.anyio
async def test_a_definition_of_every_one_of_the_twelve_field_types_is_stored_as_it_was_sent(
    client, workshop
):
    saved = put_definition(client, workshop, [one_section(EVERY_TYPE, description="one of each")])
    assert saved.status_code == 200, saved.text
    assert saved.json()["created"] == len(EVERY_TYPE)

    rows = await stored_fields(workshop)
    assert set(rows) == {f["key"] for f in EVERY_TYPE}
    for sent in EVERY_TYPE:
        row = rows[sent["key"]]
        assert row.type == sent["type"], sent["key"]
        assert row.label == sent["label"], sent["key"]
        assert row.tier == sent.get("tier", "STANDARD"), sent["key"]
        assert row.isRequired is sent.get("required", False), sent["key"]
        assert row.help == sent.get("help", ""), sent["key"]
        assert row.unit == sent.get("unit", ""), sent["key"]
        assert row.minValue == sent.get("minValue"), sent["key"]
        assert row.maxValue == sent.get("maxValue"), sent["key"]
        assert row.maxLength == (sent.get("maxLength") or None), sent["key"]
        if sent.get("options"):
            assert row.options == sent["options"], sent["key"]
        else:
            assert row.options is None, sent["key"]


def test_this_module_covers_every_field_type_v1_allows():
    covered = {f["type"] for f in EVERY_TYPE}
    assert covered == {t.value for t in V1_FIELD_TYPES}



@needs_db
@pytest.mark.anyio
async def test_an_edit_rewrites_the_row_in_place_and_bounds_can_be_cleared(client, workshop):
    first = put_definition(client, workshop, [one_section([
        {"key": "fInt", "label": "Int", "type": "INT", "unit": "looms",
         "minValue": 0, "maxValue": 99, "help": "a note"}
    ])])
    assert first.status_code == 200, first.text
    before = (await stored_fields(workshop))["fInt"]

    second = put_definition(client, workshop, [one_section([
        {"key": "fInt", "label": "Int", "type": "INT", "unit": "weavers"}
    ])])
    assert second.status_code == 200, second.text
    assert second.json()["created"] == 0
    assert second.json()["superseded"] == 0

    after = (await stored_fields(workshop))["fInt"]
    assert after.id == before.id, "the row was replaced instead of edited"
    assert after.unit == "weavers"
    assert after.help == ""
    assert after.minValue is None
    assert after.maxValue is None
    assert after.isActive is True


@needs_db
@pytest.mark.anyio
async def test_an_option_list_that_is_no_longer_offered_is_cleared_from_the_row(client, workshop):
    first = put_definition(client, workshop, [one_section([
        {"key": "fMulti", "label": "Multi enum", "type": "MULTI_ENUM",
         "options": [{"value": "a", "label": "A"}, {"value": "b", "label": "B"}]}
    ])])
    assert first.status_code == 200, first.text
    assert (await stored_fields(workshop))["fMulti"].options == [
        {"value": "a", "label": "A"}, {"value": "b", "label": "B"}
    ]

    second = put_definition(client, workshop, [one_section([
        {"key": "fMulti", "label": "Multi enum", "type": "TAGS"}
    ])])
    assert second.status_code == 200, second.text

    row = (await stored_fields(workshop))["fMulti"]
    assert row.type == "TAGS"
    assert not row.options, f"a stale option list survived the edit: {row.options!r}"
    got = client.get(f"/api/design-workshops/{workshop}/custom-sections")
    assert got.status_code == 200, got.text
    field = got.json()["sections"][0]["fields"][0]
    assert field["options"] == []



def answer(client: Any, workshop: str, data: dict[str, Any]) -> Any:
    return client.put(
        f"/api/design-workshops/{workshop}/stages/{STAGE}",
        json={"entries": [{"entityKey": CUSTOM_ENTITY_KEY, "data": data}]},
    )


@needs_db
@pytest.mark.anyio
async def test_rewording_an_answered_field_supersedes_it_under_a_new_key(client, workshop):
    assert put_definition(client, workshop, [one_section([
        {"key": "loomcount", "label": "How many looms?", "type": "INT"}
    ])]).status_code == 200
    recorded = answer(client, workshop, {"loomcount": 12})
    assert recorded.status_code == 200, recorded.text
    assert recorded.json()["errors"] == {}

    reworded = put_definition(client, workshop, [one_section([
        {"key": "loomcount", "label": "How many weavers?", "type": "INT"}
    ])])
    assert reworded.status_code == 200, reworded.text
    assert reworded.json()["superseded"] == 1

    rows = await stored_fields(workshop)
    old = rows["loomcount"]
    assert old.label == "How many looms?", "the answered wording was overwritten"
    assert old.isActive is False
    assert old.retiredAt is not None
    assert old.supersededById is not None
    successor = next(row for row in rows.values() if row.id == old.supersededById)
    assert successor.key != old.key
    assert successor.label == "How many weavers?"
    assert successor.isActive is True


@needs_db
@pytest.mark.anyio
async def test_dropping_a_field_retires_it_when_answered_and_removes_it_when_not(client, workshop):
    assert put_definition(client, workshop, [one_section([
        {"key": "answered", "label": "Answered", "type": "TEXT"},
        {"key": "untouched", "label": "Untouched", "type": "TEXT"},
    ])]).status_code == 200
    assert answer(client, workshop, {"answered": "indigo, twice"}).status_code == 200

    dropped = put_definition(client, workshop, [one_section([])])
    assert dropped.status_code == 200, dropped.text
    assert dropped.json()["retired"] == 1
    assert dropped.json()["removed"] == 1

    rows = await stored_fields(workshop)
    assert set(rows) == {"answered"}, "the answered field was deleted, or the empty one was kept"
    assert rows["answered"].isActive is False
    assert rows["answered"].retiredAt is not None
    assert rows["answered"].supersededById is None


@needs_db
@pytest.mark.anyio
async def test_the_get_returns_retired_rows_because_a_report_has_to_print_them(client, workshop):
    assert put_definition(client, workshop, [one_section([
        {"key": "loomcount", "label": "How many looms?", "type": "INT"}
    ])]).status_code == 200
    assert answer(client, workshop, {"loomcount": 12}).status_code == 200
    assert put_definition(client, workshop, [one_section([
        {"key": "loomcount", "label": "How many weavers?", "type": "INT"}
    ])]).status_code == 200

    got = client.get(f"/api/design-workshops/{workshop}/custom-sections")
    assert got.status_code == 200, got.text
    fields = got.json()["sections"][0]["fields"]
    by_label = {f["label"]: f for f in fields}
    assert set(by_label) == {"How many looms?", "How many weavers?"}
    assert by_label["How many looms?"]["retired"] is True
    assert by_label["How many weavers?"]["retired"] is False



@needs_db
@pytest.mark.anyio
async def test_a_section_is_created_edited_and_retired_through_the_same_whole_set_put(
    client, workshop
):
    from prisma import Prisma

    async def section_rows() -> list[Any]:
        own = Prisma()
        await own.connect()
        try:
            return await own.dwcustomsection.find_many(where={"designWorkshopId": workshop})
        finally:
            await own.disconnect()

    assert put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}],
        title="Dye notes", description="Where the colour came from", sortOrder=3,
    )]).status_code == 200
    created = (await section_rows())[0]
    assert created.title == "Dye notes"
    assert created.description == "Where the colour came from"
    assert created.sortOrder == 3
    assert created.isActive is True

    assert put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}],
        title="Dye and mordant notes", description="", sortOrder=0,
    )]).status_code == 200
    edited = (await section_rows())[0]
    assert edited.id == created.id
    assert edited.title == "Dye and mordant notes"
    assert edited.description == ""
    assert edited.sortOrder == 0

    assert put_definition(client, workshop, []).status_code == 200
    assert await section_rows() == []
    assert await stored_fields(workshop) == {}


@needs_db
@pytest.mark.anyio
async def test_a_definition_this_server_refuses_is_a_422_naming_every_problem_and_writes_nothing(
    client, workshop
):
    refused = put_definition(client, workshop, [one_section([
        {"key": "bad_key", "label": "Underscored", "type": "TEXT"},
        {"key": "alsoBad", "label": "Not a type", "type": "HOLOGRAM"},
    ])])
    assert refused.status_code == 422, refused.text
    assert await stored_fields(workshop) == {}

    both = put_definition(client, workshop, [one_section([
        {"key": "onlyOne", "label": "One option", "type": "ENUM",
         "options": [{"value": "indigo", "label": "Indigo"}]},
        {"key": "mustFill", "label": "Required standard", "type": "TEXT",
         "tier": "STANDARD", "required": True},
    ])])
    assert both.status_code == 422, both.text
    problems = both.json()["detail"]["problems"]
    assert len(problems) >= 2, problems
    assert any("onlyOne" in p for p in problems)
    assert any("mustFill" in p for p in problems)
    assert await stored_fields(workshop) == {}



def _two_sections() -> list[dict[str, Any]]:
    return [
        one_section([{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}]),
        one_section(
            [{"key": "loomCount", "label": "How many looms?", "type": "INT"}],
            key="looms",
            title="Looms",
        ),
    ]


@needs_db
@pytest.mark.anyio
async def test_a_stale_tab_is_refused_with_both_digests_and_deletes_nothing(client, workshop):
    first = put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}]
    )])
    assert first.status_code == 200, first.text
    stale = first.json()["customSchemaVersion"]

    second = put_definition(client, workshop, _two_sections())
    assert second.status_code == 200, second.text
    current = second.json()["customSchemaVersion"]
    assert current != stale, "adding a section must move the digest, or the check cannot work"
    assert set(await stored_fields(workshop)) == {"dyesrc", "loomCount"}

    refused = put_definition(
        client, workshop,
        [one_section([{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}])],
        customSchemaVersion=stale,
    )
    assert refused.status_code == 409, refused.text
    detail = refused.json()["detail"]
    assert detail["expected"] == stale
    assert detail["actual"] == current
    assert detail["code"] == "custom_sections_conflict"

    assert set(await stored_fields(workshop)) == {"dyesrc", "loomCount"}


@needs_db
@pytest.mark.anyio
async def test_the_current_digest_is_accepted_and_a_client_that_sends_none_keeps_the_old_behaviour(
    client, workshop
):
    first = put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}]
    )])
    assert first.status_code == 200, first.text

    fresh = put_definition(client, workshop, _two_sections(),
                           customSchemaVersion=first.json()["customSchemaVersion"])
    assert fresh.status_code == 200, fresh.text
    assert set(await stored_fields(workshop)) == {"dyesrc", "loomCount"}

    legacy = put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}]
    )])
    assert legacy.status_code == 200, legacy.text
    assert set(await stored_fields(workshop)) == {"dyesrc"}


@needs_db
@pytest.mark.anyio
async def test_a_digest_that_is_not_a_digest_is_refused_rather_than_ignored(client, workshop):
    first = put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}]
    )])
    assert first.status_code == 200, first.text

    refused = put_definition(client, workshop, [], customSchemaVersion="not-a-real-digest")
    assert refused.status_code == 409, refused.text
    assert set(await stored_fields(workshop)) == {"dyesrc"}

    misspelled = client.put(
        f"/api/design-workshops/{workshop}/custom-sections",
        json={"sections": [], "customSchemaversion": first.json()["customSchemaVersion"]},
    )
    assert misspelled.status_code == 422, misspelled.text
    assert set(await stored_fields(workshop)) == {"dyesrc"}
