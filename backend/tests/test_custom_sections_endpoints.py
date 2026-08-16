"""The custom-sections definition endpoints, against Postgres, over HTTP. The half nothing covered.

``tests/test_custom_sections.py`` holds 84 tests and is deliberately pure — no database, no network,
nothing that skips — and it is worth keeping exactly as it is: it pins the planning logic, which is
where the subtlety of this feature lives. **It also could not possibly have caught the defect that
made this feature dead on arrival**, and that is the gap this file exists to close.

── WHAT WAS WRONG, AND FOR HOW LONG ───────────────────────────────────────────────────────────────
``PUT /api/design-workshops/{id}/custom-sections`` answered **HTTP 500 to every body that contained a
field**, from the day it was written. Verbatim, against a running server with Postgres behind it:

    PUT /api/design-workshops/cmsqgwgt7004oho0s1ydi15ja/custom-sections
    {"sections":[{"key":"dyenotes","title":"Dye notes","stageKey":"CLUSTER_CRAFT_BACKGROUND",
      "fields":[{"key":"dyesrc","label":"Dye source","type":"TEXT"}]}]}

    HTTP 500
    {"detail":"Something went wrong on the server. The error has been logged.",
     "error":"MissingRequiredValueError"}

The cause was one ``None``: ``apply_definition_plan`` passed ``options=None`` for a field with no
option list, prisma-client-py renders that as ``options: null``, and the query engine refuses ``null``
for a nullable ``Json`` column. So no workshop could hold a single custom question, and everything
built on top of that — the service, the web definition editor, the handset form, the report annexure
— had never once run. The whole feature was reported as working.

── WHY 84 PASSING TESTS DID NOT NOTICE ────────────────────────────────────────────────────────────
Because the defect is not in a decision. It is in the one step that has no decision in it: handing a
finished plan to the driver. ``plan_definition`` was right, every rule it enforces was right, and the
tests that assert those rules were right — and then the write failed. A suite with no database under
it cannot reach the only line that was wrong, and no amount of adding to it would have changed that.

So the standard here is different: **every test in this file performs a real request against the real
app with the real driver, and then reads the stored row back out of Postgres to check it.** Not the
response body — the row. A response is assembled by the same process that did the write and will
happily agree with itself.

Everything below skips when ``DATABASE_URL`` is not local, the same guard and for the same reason as
``test_questionnaire_forms.py``, ``test_designer_roster.py`` and ``test_controlled_vocabularies.py``:

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

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

#: The stage the sections hang off. Any real ``StageSpec.key`` would do — the endpoint validates it
#: against ``stages()`` — and this one is used because it is the stage the defect was reproduced on.
STAGE = "CLUSTER_CRAFT_BACKGROUND"

#: One field of every type v1 allows, in one section.
#:
#: **THE LIST IS ASSERTED AGAINST ``V1_FIELD_TYPES`` RATHER THAN TRUSTED**, in
#: :func:`test_this_module_covers_every_field_type_v1_allows`. A thirteenth type added to the registry
#: without a row here would leave this file claiming a coverage it no longer has, and the coverage
#: claim is the whole point of the file — the last one was believed for the life of the endpoint.
#:
#: Keys are alphanumeric after the first letter because ``KEY_PATTERN`` says so; an underscore is a
#: 422 with a sentence about orphaned answers, which is correct and is asserted elsewhere.
EVERY_TYPE: tuple[dict[str, Any], ...] = (
    {"key": "fText", "label": "Text", "type": "TEXT", "help": "a note", "maxLength": 40},
    {"key": "fLong", "label": "Long text", "type": "LONG_TEXT"},
    {"key": "fInt", "label": "Int", "type": "INT", "unit": "looms", "minValue": 0, "maxValue": 99},
    {"key": "fDec", "label": "Decimal", "type": "DECIMAL", "minValue": 0.5, "maxValue": 9.5},
    {"key": "fMoney", "label": "Money", "type": "MONEY", "minValue": 0},
    {"key": "fPct", "label": "Percent", "type": "PERCENT", "minValue": 0, "maxValue": 100},
    {"key": "fDate", "label": "Date", "type": "DATE"},
    {"key": "fTime", "label": "Time", "type": "TIME"},
    # BASIC because only a BASIC field may be required — `validate_registry` rule 3, so this row is
    # also the one that proves `isRequired` and `tier` survive the round trip together.
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
    """A signed-in designer, the admin who opens workshops for them, and a live TestClient sharing
    the app's Prisma connection.

    The accounts are created here rather than inside a test because the Prisma client is shared with
    the running app and bound to the TestClient's event loop; touching it from a test's own loop is
    the kind of cross-loop use that fails intermittently rather than honestly. Where a test needs to
    read a row for itself it opens its OWN client — see :func:`stored_fields`, which is the same
    thing ``test_questionnaire_forms`` does for the foreign-key floor.

    The addresses carry a per-run stamp because ``User.email`` is unique, so a fixed one would pass
    on a clean database and fail on every run after.

    THE ADMIN AND THE ROSTER ROW ARE BOTH LOAD-BEARING, and both arrived with the create rule.
    Only admins and the master admin may START a design workshop (``can_create_design_workshops``),
    so the :func:`workshop` fixture below has to open it as the admin and hand it to the designer;
    and ``replace_viewers`` refuses to grant an account the ACTIVE designer roster does not admit,
    so without the roster row the grant 422s and every test here would run against a workshop the
    designer cannot open. The alternative — running this whole module as an admin — would have been
    two lines shorter and would have stopped proving that a DESIGNER can define custom sections,
    which is the only kind of account that ever does it in the field.
    """
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
        # The DESIGNER is the default identity: every test in this module is about what a designer
        # may do with a workshop's custom sections. The admin is used for exactly one call.
        client.headers.update(
            {"Authorization": f"Bearer {create_access_token(subject=designer.id)}"}
        )
        yield {"client": client, "designer": designer, "admin": admin, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _as_admin(world) -> dict[str, str]:
    """The header for the one account that may open a workshop."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


@pytest.fixture
def workshop(client, world):
    """A fresh workshop per test, so no test can be made to pass by another one's leftovers.

    OPENED BY THE ADMIN, GRANTED TO THE DESIGNER — the real flow since designers stopped being able
    to create workshops. The grant is what lets the designer reach it at all: without a
    ``DesignWorkshopViewer`` row, ``load_workshop_or_404`` answers 404 to anyone but the creator and
    the admins, and every test in this file would fail on its first request with a message about a
    missing workshop rather than about custom sections.
    """
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
    """A whole-set PUT, optionally carrying the digest the caller loaded.

    THE DIGEST IS OMITTED BY DEFAULT AND THAT IS ON PURPOSE, not laziness: every existing test in
    this file is a client that predates the stale-tab check, so leaving them alone is what proves the
    field is genuinely optional. A default of ``""`` would have quietly opted all of them in.
    """
    body: dict[str, Any] = {"sections": sections}
    if customSchemaVersion is not None:
        body["customSchemaVersion"] = customSchemaVersion
    return client.put(f"/api/design-workshops/{workshop}/custom-sections", json=body)


def one_section(fields: tuple[dict[str, Any], ...] | list[dict[str, Any]], **over: Any) -> dict:
    section = {"key": "dyenotes", "title": "Dye notes", "stageKey": STAGE, "fields": list(fields)}
    section.update(over)
    return section


async def stored_fields(workshop: str) -> dict[str, Any]:
    """The ``DwCustomField`` ROWS for a workshop, keyed by field key, read on our own connection.

    THE POINT OF READING THE TABLE AND NOT THE RESPONSE. The response to the PUT is assembled by the
    same process that performed the write, from the same objects, so it agrees with itself whatever
    reached Postgres — and what reached Postgres is precisely what was wrong. A second connection
    asking the database what it actually holds is the only witness that is independent of the bug.
    """
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


# --------------------------------------------------------------------------------------
# 1. The blocker itself
# --------------------------------------------------------------------------------------


@needs_db
@pytest.mark.anyio
async def test_the_exact_body_that_answered_500_for_the_life_of_this_endpoint_now_saves(
    client, workshop
):
    """THE REGRESSION TEST, and it is one field with no options — the whole of what it took.

    Byte for byte the body from the reproduction in this module's docstring. It is asserted on its
    own, before the twelve-type sweep, because the twelve-type body contains ENUM rows that DO carry
    options and would still have exercised the working branch: the failing input is the ABSENCE of an
    option list, so the smallest possible definition is the sharpest possible test.

    ``options`` is read back as ``None`` and not as ``[]`` — the column holds no list at all, which
    is what "this is not a choice field" means here, and it is what :func:`_option_list` turns into
    no options for every reader of the table.
    """
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
    """All twelve types in one write, then every column compared against what was sent.

    Every type, because the defect was in a step that runs once per field regardless of type, and a
    test that covered TEXT alone would have said this endpoint worked while ENUM still did not.
    """
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
        # `maxLength` is stored as NULL rather than 0 for "no limit" — `_field_columns` writes
        # `f.max_length or None` — so the absence of a limit is one value in the column and not two.
        assert row.maxLength == (sent.get("maxLength") or None), sent["key"]
        if sent.get("options"):
            assert row.options == sent["options"], sent["key"]
        else:
            assert row.options is None, sent["key"]


def test_this_module_covers_every_field_type_v1_allows():
    """The coverage claim, checked rather than asserted in prose. Needs no database.

    A thirteenth type added to ``V1_FIELD_TYPES`` without a row in :data:`EVERY_TYPE` fails HERE,
    with a name, rather than leaving the sweep above quietly covering eleven twelfths of the feature
    while its docstring says otherwise. This file exists because a coverage claim was believed.
    """
    covered = {f["type"] for f in EVERY_TYPE}
    assert covered == {t.value for t in V1_FIELD_TYPES}


# --------------------------------------------------------------------------------------
# 2. The sibling write paths: edit in place, and clearing an option list
# --------------------------------------------------------------------------------------


@needs_db
@pytest.mark.anyio
async def test_an_edit_rewrites_the_row_in_place_and_bounds_can_be_cleared(client, workshop):
    """A second PUT that changes only settings edits the same row — no new id, nothing retired.

    The nullable SCALAR columns are cleared here on purpose. They take a ``null`` from this driver
    without complaint, which is exactly why the ``options`` failure was so hard to see: three of the
    four nulls in this write have always worked.
    """
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
    """A MULTI_ENUM retyped as TAGS must not keep yesterday's picker in the column.

    THE OTHER HALF OF THE ``options`` FIX, and the half a create-only test cannot see. The obvious
    repair for the 500 is "omit the key when there is nothing to write", which is correct on a create
    and silently wrong on an update: an omitted key means *leave this column alone*, so the stale
    option list would survive, the response would say 200, and a field the designer had turned into
    free text would still offer Indigo and Madder — for ever, with nothing anywhere saying so. The
    update therefore has to WRITE the emptiness, and this is the test that fails if it stops.
    """
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
    # Read back through the endpoint too, because the picker is drawn from THIS shape.
    got = client.get(f"/api/design-workshops/{workshop}/custom-sections")
    assert got.status_code == 200, got.text
    field = got.json()["sections"][0]["fields"][0]
    assert field["options"] == []


# --------------------------------------------------------------------------------------
# 3. What an edit does to answers already recorded
#
# The rule this feature borrows whole from `services/questionnaire_forms.py`, checked here against
# the database rather than against a plan: "How many looms?" answered "12", reworded to "How many
# weavers?", and a ministry report now states there are twelve weavers.
# --------------------------------------------------------------------------------------


def answer(client: Any, workshop: str, data: dict[str, Any]) -> Any:
    """Record custom answers through the stage endpoint, where a handset would put them."""
    return client.put(
        f"/api/design-workshops/{workshop}/stages/{STAGE}",
        json={"entries": [{"entityKey": CUSTOM_ENTITY_KEY, "data": data}]},
    )


@needs_db
@pytest.mark.anyio
async def test_rewording_an_answered_field_supersedes_it_under_a_new_key(client, workshop):
    """The answer stays under the wording it was given, and the new wording is a new row.

    This is the SUPERSEDE path, which is the third of the three places that write a field row, and it
    was refused by the driver like the other two: a supersede CREATES the replacement. A definition
    edit made after anybody has answered anything went through this line, so it was unreachable.
    """
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
    # The replacement is a NEW row under a NEW key, so the recorded 12 cannot be read under the new
    # question. That is the whole rule: a report can still say twelve LOOMS and can never say twelve
    # weavers.
    successor = next(row for row in rows.values() if row.id == old.supersededById)
    assert successor.key != old.key
    assert successor.label == "How many weavers?"
    assert successor.isActive is True


@needs_db
@pytest.mark.anyio
async def test_dropping_a_field_retires_it_when_answered_and_removes_it_when_not(client, workshop):
    """A REPLACE IS NOT A DELETE — for the answered field. For the untouched one it is.

    Both halves in one test because the distinction is the point: absent-from-the-body means two
    different things depending on whether a designer's answer is riding on the key, and a test of
    either half alone would pass for an implementation that always did that one thing.
    """
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
    # Retired, NOT superseded: nobody reworded it, so there is no replacement to point at.
    assert rows["answered"].supersededById is None


@needs_db
@pytest.mark.anyio
async def test_the_get_returns_retired_rows_because_a_report_has_to_print_them(client, workshop):
    """``includeRetired`` is not a debugging convenience — a retired wording still holds an answer.

    A copy of a definition missing every superseded wording makes two copies of one report disagree
    about the fieldwork, with nothing in either saying so. So the GET carries them, and the client
    OFFERS the live ones and PRINTS the retired ones.
    """
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


# --------------------------------------------------------------------------------------
# 4. The section row, and the refusals — on the wire, where the 500 was
# --------------------------------------------------------------------------------------


@needs_db
@pytest.mark.anyio
async def test_a_section_is_created_edited_and_retired_through_the_same_whole_set_put(
    client, workshop
):
    """The SECTION row's three write paths, which are the sibling inputs to the field's three."""
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

    # Nothing under it has been answered, so an absent section is REMOVED rather than retired — and
    # its field goes with it through the CASCADE.
    assert put_definition(client, workshop, []).status_code == 200
    assert await section_rows() == []
    assert await stored_fields(workshop) == {}


@needs_db
@pytest.mark.anyio
async def test_a_definition_this_server_refuses_is_a_422_naming_every_problem_and_writes_nothing(
    client, workshop
):
    """A refusal must not be a 500 and must not half-apply. Both were live risks, not hypotheses.

    ONE 422 LISTING EVERY VIOLATION, because the definition editor shows them against the rows they
    name and a designer who makes six round trips to learn six things stops using the feature after
    the third. And the transaction is checked: a body refused after a valid section would otherwise
    leave the valid half behind.
    """
    refused = put_definition(client, workshop, [one_section([
        {"key": "bad_key", "label": "Underscored", "type": "TEXT"},
        {"key": "alsoBad", "label": "Not a type", "type": "HOLOGRAM"},
    ])])
    assert refused.status_code == 422, refused.text
    assert await stored_fields(workshop) == {}

    # A single-option ENUM and a required STANDARD-tier field: two rules, one response.
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


# --------------------------------------------------------------------------------------
# The stale tab
#
# `PUT /custom-sections` replaces the WHOLE definition, so without a version check it is
# last-write-wins over every question in the workshop. This was measured on the wire before it was
# fixed, and the sequence below is that measurement turned into a test:
#
#   1. designer 1 saves one section          -> version f2e0b0a8ca5bcc4b  sections ['dye']
#   2. designer 2 adds a second section      -> version 68c212eec44f5cfc  sections ['dye','looms']
#   3. designer 1's STALE tab presses Save   -> HTTP 200
#                                               version f2e0b0a8ca5bcc4b  sections ['dye']  removed 1
#
# The `looms` section and both its fields were gone — REMOVED rather than retired, correctly by
# `plan_definition`'s own rule, because nothing had answered them yet. No 409, no warning, nothing on
# either screen.
# --------------------------------------------------------------------------------------


def _two_sections() -> list[dict[str, Any]]:
    """The colleague's definition: this tab's section, plus one it has never seen."""
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
    """The measured sequence above, with the check in place. Read from the TABLE, not the response.

    THE ASSERTION THAT MATTERS IS THE LAST ONE. A 409 that still performed the write would be a
    worse defect than the one it replaced, because the client is now told its work did not land while
    the colleague's section is deleted anyway.
    """
    first = put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}]
    )])
    assert first.status_code == 200, first.text
    stale = first.json()["customSchemaVersion"]

    # The colleague adds a section. This tab knows nothing about it.
    second = put_definition(client, workshop, _two_sections())
    assert second.status_code == 200, second.text
    current = second.json()["customSchemaVersion"]
    assert current != stale, "adding a section must move the digest, or the check cannot work"
    assert set(await stored_fields(workshop)) == {"dyesrc", "loomCount"}

    # The stale tab presses Save. Its body names only the section it knows about.
    refused = put_definition(
        client, workshop,
        [one_section([{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}])],
        customSchemaVersion=stale,
    )
    assert refused.status_code == 409, refused.text
    detail = refused.json()["detail"]
    # BOTH DIGESTS, NAMED. Without them the editor cannot tell a genuine conflict from its own stale
    # cache, and a bug report about this has nothing in it.
    assert detail["expected"] == stale
    assert detail["actual"] == current
    assert detail["code"] == "custom_sections_conflict"

    # The colleague's question is still being asked.
    assert set(await stored_fields(workshop)) == {"dyesrc", "loomCount"}


@needs_db
@pytest.mark.anyio
async def test_the_current_digest_is_accepted_and_a_client_that_sends_none_keeps_the_old_behaviour(
    client, workshop
):
    """The two halves that stop the fix from being either useless or a breaking change.

    A CHECK THAT REFUSED A CURRENT DIGEST would make the editor unusable, and a check that refused an
    ABSENT one would break every handset and browser already in the field on the day it shipped —
    which is why `customSchemaVersion` is optional on `CustomSectionsIn` and why the second half of
    this test deliberately asserts the UNPROTECTED behaviour rather than the safe one.
    """
    first = put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}]
    )])
    assert first.status_code == 200, first.text

    # Sending back exactly what the last write returned is accepted.
    fresh = put_definition(client, workshop, _two_sections(),
                           customSchemaVersion=first.json()["customSchemaVersion"])
    assert fresh.status_code == 200, fresh.text
    assert set(await stored_fields(workshop)) == {"dyesrc", "loomCount"}

    # And a client that does not know the field is exactly as (un)safe as it was yesterday: this body
    # is stale in every sense except that it says so, and it still wins. That is the documented trade.
    legacy = put_definition(client, workshop, [one_section(
        [{"key": "dyesrc", "label": "Dye source", "type": "TEXT"}]
    )])
    assert legacy.status_code == 200, legacy.text
    assert set(await stored_fields(workshop)) == {"dyesrc"}


@needs_db
@pytest.mark.anyio
async def test_a_digest_that_is_not_a_digest_is_refused_rather_than_ignored(client, workshop):
    """A misspelled or truncated digest must 409, not fall through to last-write-wins.

    The failure this guards against is a client that sends the field under a value it computed
    itself: it would never match, so it fails CLOSED here. `extra="forbid"` covers the other half —
    a client that misspells the KEY is refused by validation rather than silently unprotected.
    """
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
