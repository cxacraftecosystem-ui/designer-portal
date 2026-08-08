"""The offline-sync rules of ``save_stage``, against a real database.

These are the behaviours that decide whether a designer's two weeks of fieldwork survives the
walk back to signal. They cannot be tested without Postgres — the failure this file exists for
was a UNIQUE index doing something the Python could not see — so the module skips itself when
``DATABASE_URL`` does not point at a local database.

Run the local stack first:

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import os
import uuid

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.rich_text import to_plain

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
async def client():
    """A TestClient with a signed-in admin, sharing one Prisma connection with the app."""
    from fastapi.testclient import TestClient

    from app.main import app

    email = f"sync-test-{uuid.uuid4().hex[:8]}@example.org"
    await db.connect()
    try:
        user = await db.user.create(data={
            "email": email, "name": "Sync Test", "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
    finally:
        await db.disconnect()

    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield c


@pytest.fixture
def workshop(client):
    response = client.post("/api/design-workshops", json={"title": "Sync test workshop"})
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _sketches(client, workshop_id, rows, *, replace=True, emptied=None):
    body = {
        "entries": [
            {"entityKey": "sketch", "ordinal": i, "data": row}
            for i, row in enumerate(rows)
        ],
        "replaceCollections": replace,
    }
    if emptied is not None:
        body["emptiedEntities"] = emptied
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/SKETCH_DEVELOPMENT", json=body
    )


def _setup_entry(client, workshop_id):
    """The WORKSHOP_SETUP singleton as stored, for the merge test below."""
    payload = client.get(
        f"/api/design-workshops/{workshop_id}/stages/WORKSHOP_SETUP"
    ).json()
    return payload["singleton"]


def _rows(client, workshop_id):
    payload = client.get(
        f"/api/design-workshops/{workshop_id}/stages/SKETCH_DEVELOPMENT"
    ).json()
    return payload["collections"].get("sketch", [])


A = {"_clientKey": "sk-a", "sketchNo": "SK-01", "name": "Runner", "image": "m1"}
B = {"_clientKey": "sk-b", "sketchNo": "SK-02", "name": "Stole", "image": "m2"}


async def test_a_repeated_sync_updates_rather_than_duplicating(client, workshop):
    """The phone reconnects and replays a queue it already sent.

    Without a stable client key the server cannot tell "the row you already have" from "a new
    row", and every reconnect duplicates the whole collection — the most common way an
    offline-first app corrupts its own data.
    """
    first = _sketches(client, workshop, [A, B]).json()
    assert first["created"] == 2

    again = _sketches(client, workshop, [A, B]).json()
    assert again["created"] == 0
    assert again["updated"] == 2
    assert len(_rows(client, workshop)) == 2


async def test_removing_a_row_on_the_client_removes_it_here(client, workshop):
    _sketches(client, workshop, [A, B])
    result = _sketches(client, workshop, [A]).json()
    assert result["removed"] == 1
    assert [r["name"] for r in _rows(client, workshop)] == ["Runner"]


async def test_re_adding_a_deleted_client_key_resurrects_the_row(client, workshop):
    """THE REGRESSION THIS FILE EXISTS FOR.

    The unique index is (designWorkshopId, entityKey, clientKey) and carries no ``deletedAt``,
    so a soft-deleted row still occupies its client key. Matching only live rows made the
    matcher blind to it, the save fell through to an INSERT, and the index refused — a bare 500
    that failed the ENTIRE stage.

    The path is ordinary: a designer deletes a sketch and undoes it, or a phone that never got
    the acknowledgement replays its queue. The row must come back, and it must come back with
    the SAME id, because prototypes and reviews reference it.
    """
    _sketches(client, workshop, [A, B])
    before = {r["name"]: r["_entryId"] for r in _rows(client, workshop)}

    _sketches(client, workshop, [A])                      # delete B
    response = _sketches(client, workshop, [A, B])        # undo

    assert response.status_code == 200, response.text
    assert response.json()["created"] == 0, "the row must be resurrected, not re-inserted"

    after = {r["name"]: r["_entryId"] for r in _rows(client, workshop)}
    assert set(after) == {"Runner", "Stole"}
    assert after["Stole"] == before["Stole"], "resurrecting must preserve the row's id"


async def test_an_already_deleted_row_is_not_reported_as_removed_again(client, workshop):
    """Otherwise every later save rewrites deletedAt to today and destroys the record of when
    the designer actually removed the row."""
    _sketches(client, workshop, [A, B])
    assert _sketches(client, workshop, [A]).json()["removed"] == 1
    assert _sketches(client, workshop, [A]).json()["removed"] == 0


async def test_two_workshops_may_use_the_same_client_key(client):
    """Client keys are unique within a workshop, not globally: two workshops captured on the
    same phone will legitimately number their first sketch the same way."""
    one = client.post("/api/design-workshops", json={"title": "One"}).json()["id"]
    two = client.post("/api/design-workshops", json={"title": "Two"}).json()["id"]
    assert _sketches(client, one, [A]).status_code == 200
    assert _sketches(client, two, [A]).status_code == 200


async def test_many_rows_without_a_client_key_coexist(client, workshop):
    """The web sets no client key. Postgres treats NULLs as distinct in a unique index, which
    is what lets the browser create rows freely — but only as long as nothing substitutes an
    empty string for the missing key."""
    rows = [{"sketchNo": f"W-{i}", "name": f"Web {i}", "image": "m1"} for i in range(4)]
    response = _sketches(client, workshop, rows)
    assert response.status_code == 200, response.text
    assert len(_rows(client, workshop)) == 4


async def test_replace_collections_false_leaves_other_rows_alone(client, workshop):
    """The web edits one row at a time and must not delete rows another editor added."""
    _sketches(client, workshop, [A, B])
    result = _sketches(client, workshop, [A], replace=False).json()
    assert result["removed"] == 0
    assert len(_rows(client, workshop)) == 2


async def test_a_singleton_is_updated_not_duplicated(client, workshop):
    for title in ("First concept", "Revised concept"):
        response = client.put(
            f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF",
            json={"entries": [{"entityKey": "designBrief", "data": {
                "concept": title, "targetCategories": ["TABLE_LINEN"]}}]},
        )
        assert response.status_code == 200, response.text

    payload = client.get(
        f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF"
    ).json()
    # Through ``to_plain`` rather than compared raw: ``concept`` is a NARRATIVE field and so is
    # RICH_TEXT, which stores a block document. The claim this test makes is about the singleton
    # being updated rather than duplicated, and it should hold whichever of the two prose types
    # the registry gives the field — a raw comparison would fail on a promotion that lost nothing.
    assert to_plain(payload["singleton"]["concept"]) == "Revised concept"


async def test_a_soft_deleted_workshop_keeps_its_stage_data(client, workshop):
    """Deletion is recoverable because one row here is weeks of somebody's fieldwork."""
    _sketches(client, workshop, [A, B])
    assert client.delete(f"/api/design-workshops/{workshop}").status_code == 204
    assert client.post(f"/api/design-workshops/{workshop}/restore").status_code == 200
    assert len(_rows(client, workshop)) == 2


async def test_editing_a_deleted_workshop_is_refused(client, workshop):
    """A 409 rather than a silent write into a record the owner believes is gone."""
    client.delete(f"/api/design-workshops/{workshop}")
    assert _sketches(client, workshop, [A]).status_code == 409
    client.post(f"/api/design-workshops/{workshop}/restore")


async def test_deleting_the_last_row_of_a_collection_actually_removes_it(client, workshop):
    """THE SECOND REGRESSION THIS FILE EXISTS FOR.

    An emptied collection is INVISIBLE in ``entries`` — the web builds them from
    ``collections[key] ?? []`` and the phone from ``.orEmpty()`` — which is exactly what both
    clients send once the designer deletes the last row. The save answered ``removed: 0``, the
    UI said the stage was saved, and the rows stayed alive: back on the next load, and printed
    in the .docx handed to the ministry. With no per-row delete endpoint, no client action
    could ever remove them.

    The client says so explicitly now, because the alternative — sweeping every collection the
    stage declares — deleted collections the payload had never mentioned (see the test below).
    """
    _sketches(client, workshop, [A, B])
    assert len(_rows(client, workshop)) == 2

    result = _sketches(client, workshop, [], emptied=["sketch"]).json()
    assert result["removed"] == 2, "an emptied collection must be swept"
    assert _rows(client, workshop) == []


async def test_a_payload_that_never_named_an_entity_cannot_delete_it(client, workshop):
    """THE THIRD REGRESSION THIS FILE EXISTS FOR, and the costliest so far.

    ``replaceCollections`` defaults to TRUE, so a caller that omits it is armed without saying
    so. The sweep was scoped by the stage SPEC rather than by the payload, so one PUT carrying a
    single ``costSheet`` row soft-deleted every ``buyerLink``, ``costMaterialLine`` and
    ``costLabourLine`` of the same stage — and answered ``removed: 1`` with a 200, so the UI
    reported a successful save. The flagship seeded workshop was left in exactly that state: its
    cost sheets and buyer links gone, its report printing 28 unattributed material lines with no
    product, no total, no price and no buyer linkage.

    Both entities are sent first so the deletion has something to destroy, then only one of them
    is re-sent, exactly as the reproduction did.
    """
    path = f"/api/design-workshops/{workshop}/stages/COSTING_MARKET_LINKAGE"
    seeded = client.put(path, json={"entries": [
        {"entityKey": "costSheet", "data": {
            "_clientKey": "cs-1", "materialCost": "1650.00", "labourCost": "900.00",
            "expectedPrice": "3200.00"}},
        {"entityKey": "buyerLink", "data": {"_clientKey": "bl-1", "buyerName": "Utkalika"}},
    ]})
    assert seeded.status_code == 200, seeded.text
    assert seeded.json()["created"] == 2

    # replaceCollections deliberately OMITTED: the default is true, which is what makes this the
    # ordinary case rather than an exotic one.
    again = client.put(path, json={"entries": [
        {"entityKey": "costSheet", "data": {
            "_clientKey": "cs-1", "materialCost": "1650.00", "labourCost": "900.00",
            "expectedPrice": "3300.00"}},
    ]})
    assert again.status_code == 200, again.text
    assert again.json()["removed"] == 0, "an entity the payload never mentioned must survive"

    collections = client.get(path).json()["collections"]
    assert len(collections.get("buyerLink", [])) == 1, "the buyer link must still be there"
    assert collections["buyerLink"][0]["buyerName"] == "Utkalika"


async def test_emptying_one_collection_does_not_sweep_its_siblings(client, workshop):
    """The two rules together: naming an emptied entity deletes THAT entity and only it."""
    path = f"/api/design-workshops/{workshop}/stages/COSTING_MARKET_LINKAGE"
    client.put(path, json={"entries": [
        {"entityKey": "costSheet", "data": {
            "_clientKey": "cs-1", "materialCost": "1.00", "labourCost": "1.00",
            "expectedPrice": "2.00"}},
        {"entityKey": "buyerLink", "data": {"_clientKey": "bl-1", "buyerName": "Utkalika"}},
    ]})

    result = client.put(path, json={
        "entries": [{"entityKey": "buyerLink", "data": {
            "_clientKey": "bl-1", "buyerName": "Utkalika"}}],
        "emptiedEntities": ["costSheet"],
    }).json()
    assert result["removed"] == 1

    collections = client.get(path).json()["collections"]
    assert collections.get("costSheet", []) == []
    assert len(collections.get("buyerLink", [])) == 1


async def test_an_emptied_entity_is_ignored_when_nothing_is_being_replaced(client, workshop):
    """``replaceCollections: false`` means "merge, delete nothing" and outranks the list — the
    web sends it while editing one row at a time and must not delete rows another editor added.
    """
    _sketches(client, workshop, [A, B])
    result = _sketches(client, workshop, [], replace=False, emptied=["sketch"]).json()
    assert result["removed"] == 0
    assert len(_rows(client, workshop)) == 2


async def test_an_empty_payload_never_sweeps_a_singleton(client, workshop):
    """The sweep covers COLLECTION entities only. A singleton is updated or created, never
    deleted by omission — otherwise saving a stage's collection would erase its narrative."""
    client.put(
        f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF",
        json={"entries": [{"entityKey": "designBrief", "data": {
            "concept": "Keep me", "targetCategories": ["TABLE_LINEN"]}}]},
    )
    client.put(f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF", json={"entries": []})

    payload = client.get(f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF").json()
    assert to_plain(payload["singleton"].get("concept")) == "Keep me"


async def test_a_workshop_titled_in_a_non_latin_script_can_still_be_exported(client):
    """Every ASGI header value is encoded latin-1, and ``str.isalnum()`` is true for every
    Unicode letter — so an Odia workshop title reached ``content-disposition`` verbatim and
    raised inside Starlette, as a bare 500 after the handler had already returned. On an app
    built for Indian craft clusters that made the primary deliverable impossible for a large
    share of records, with no in-app workaround.
    """
    workshop_id = client.post(
        "/api/design-workshops", json={"title": "ସମ୍ବଲପୁରୀ ଇକତ କର୍ମଶାଳା"}
    ).json()["id"]

    for fmt in ("DOCX", "PDF"):
        response = client.post(
            f"/api/design-workshops/{workshop_id}/report", json={"formats": [fmt]}
        )
        assert response.status_code == 200, response.text
        disposition = response.headers["content-disposition"]
        # RFC 6266: an ASCII fallback every reader understands, plus the real name.
        assert disposition.isascii()
        assert "filename*=UTF-8''" in disposition


async def test_a_failed_export_records_no_phantom_row(client):
    """The export row used to be written before the response headers were built, so each 500
    left a record of a file nobody ever received."""
    workshop_id = client.post(
        "/api/design-workshops", json={"title": "कार्यशाला"}
    ).json()["id"]
    client.post(f"/api/design-workshops/{workshop_id}/report", json={"formats": ["DOCX"]})

    exports = client.get(f"/api/design-workshops/{workshop_id}/exports").json()
    assert len(exports) == 1
    assert exports[0]["checksumSha256"]


async def test_a_rejected_value_does_not_destroy_the_one_already_stored(client, workshop):
    """``validate_entry`` omits a field it could not read, so writing the cleaned data wholesale
    DELETED the good value the designer had saved earlier. Type "6500", save; later fat-finger
    "65OO", and the price is not merely un-updated, it is gone — while the response reports a
    validation error, which reads as "your edit was rejected", not "your earlier answer was
    deleted"."""
    path = f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE"
    good = {"_clientKey": "p1", "name": "Saree", "price": "6500.00", "material": "Cotton"}
    client.put(path, json={"entries": [{"entityKey": "existingProduct", "data": good}]})

    typo = {**good, "name": "Saree updated", "price": "65OO"}
    response = client.put(
        path, json={"entries": [{"entityKey": "existingProduct", "data": typo}]}
    )
    assert response.json()["errors"], "the bad value must still be reported"

    row = client.get(path).json()["collections"]["existingProduct"][0]
    assert row["price"] == "6500.00", "the stored price must survive a rejected edit"
    assert row["name"] == "Saree updated", "every other field on the entry must still save"


async def test_a_stage_save_never_demotes_a_status_the_designer_set(client, workshop):
    """Correcting a typo in a submitted report must not un-submit it."""
    path = f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE"
    client.patch(f"/api/design-workshops/{workshop}", json={"status": "COMPLETE"})
    client.put(path, json={"entries": [{"entityKey": "existingProduct", "data": {
        "name": "Saree", "price": "1.00"}}]})
    assert client.get(f"/api/design-workshops/{workshop}").json()["status"] == "COMPLETE"


async def test_a_draft_still_advances_on_its_first_save(client, workshop):
    """A list that still calls a workshop a draft after two weeks of capture is misleading."""
    assert client.get(f"/api/design-workshops/{workshop}").json()["status"] == "DRAFT"
    client.put(
        f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE",
        json={"entries": [{"entityKey": "existingProduct", "data": {
            "name": "Saree", "price": "1.00"}}]},
    )
    assert client.get(f"/api/design-workshops/{workshop}").json()["status"] == "IN_PROGRESS"


async def test_a_promoted_column_can_be_cleared_and_others_are_untouched(client, workshop):
    """Skipping a blank promoted value made the column write-once: a designer who typed the
    wrong sanction number, cleared it and saved found the list still showing the wrong number,
    with the JSON and the column permanently disagreeing about the same fact."""
    path = f"/api/design-workshops/{workshop}/stages/WORKSHOP_SETUP"
    base = {"workshopTitle": "T", "craftName": "Ikat"}
    client.put(path, json={"entries": [{"entityKey": "workshopSetup",
                                        "data": {**base, "workshopCode": "WRONG-1"}}]})
    assert client.get(f"/api/design-workshops/{workshop}").json()["workshopCode"] == "WRONG-1"

    client.put(path, json={"entries": [{"entityKey": "workshopSetup",
                                        "data": {**base, "workshopCode": ""}}]})
    header = client.get(f"/api/design-workshops/{workshop}").json()
    assert header["workshopCode"] is None
    assert header["craftName"] == "Ikat", "clearing one column must not blank the others"


async def test_saving_one_stage_does_not_blank_another_stages_columns(client, workshop):
    """Only the entities in THIS payload may write their promoted columns."""
    client.put(
        f"/api/design-workshops/{workshop}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup", "data": {
            "workshopTitle": "T", "craftName": "Ikat", "state": "Odisha"}}]},
    )
    client.put(
        f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE",
        json={"entries": [{"entityKey": "existingProduct", "data": {
            "name": "Saree", "price": "1.00"}}]},
    )
    header = client.get(f"/api/design-workshops/{workshop}").json()
    assert header["craftName"] == "Ikat"
    assert header["state"] == "Odisha"


async def test_two_entries_sharing_one_client_key_do_not_500_the_stage(client, workshop):
    """A client that generated the same id twice used to fail the whole stage inside the unique
    index. Both rows must still save; the collision is reported so the client can be fixed."""
    path = f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE"
    response = client.put(path, json={"entries": [
        {"entityKey": "existingProduct", "ordinal": 0,
         "data": {"_clientKey": "dup", "name": "A", "price": "1.00"}},
        {"entityKey": "existingProduct", "ordinal": 1,
         "data": {"_clientKey": "dup", "name": "B", "price": "2.00"}},
    ]})
    assert response.status_code == 200, response.text
    assert any("duplicate in payload" in k for k in response.json()["droppedKeys"])
    rows = client.get(path).json()["collections"]["existingProduct"]
    assert sorted(r["name"] for r in rows) == ["A", "B"], "neither row may be lost"


async def test_a_status_filter_the_enum_does_not_have_is_a_422_not_a_500(client):
    """END TO END, because the failure was end to end: `where["status"] = statusFilter` put the
    raw query string into a Postgres enum column and Prisma answered
    {"error": "FieldNotFoundError"} — a bare 500 with a stack trace in the log, for a lowercase
    "draft", for an "ALL" from a client whose dropdown labels its empty option, or for a stale
    bookmarked URL."""
    for bad in ("draft", "in_progress", "ALL", "NOPE"):
        response = client.get("/api/design-workshops", params={"statusFilter": bad})
        assert response.status_code == 422, f"{bad!r} -> {response.status_code} {response.text}"
        assert "DRAFT" in response.text, "the 422 must name the values a client can send"

    assert client.get(
        "/api/design-workshops", params={"statusFilter": "DRAFT"}
    ).status_code == 200


async def test_a_nul_byte_in_a_filter_is_a_result_set_and_not_a_server_error(client):
    """A `text` column cannot hold 0x00, so the driver raised and — because this is a query
    PARAMETER — it surfaced as a 500. Every such request was a logged server error with a stack
    trace, and the web renders any 5xx from a list endpoint as "you are offline", so the operator
    was shown a connectivity story about a malformed input."""
    for parameter in ("search", "craftName", "state"):
        response = client.get("/api/design-workshops", params={parameter: "\x00"})
        assert response.status_code == 200, f"{parameter} -> {response.status_code}"
        assert "items" in response.json()


async def test_a_workshop_note_is_bounded_like_every_field_beside_it(client):
    """`notes` was the ONE field on this body with no `max_length` while title, craftName, state
    and workshopId all had one — and it is the field whose cost is paid by everybody else.
    `workshop_summary` returns it in full and the LIST endpoint serialises it, so one 20 MB note
    is 20 MB added to every page of workshops anyone loads. At pageSize 100 that is a ~2 GB
    response the server assembles in memory, and nothing upstream stops the write: there is no
    body-size middleware and nginx allows 200M bodies."""
    from app.schemas.design_workshops import MAX_NOTES_CHARS

    over = client.post("/api/design-workshops", json={
        "title": "Bounded note", "notes": "x" * (MAX_NOTES_CHARS + 1),
    })
    assert over.status_code == 422, over.text

    ok = client.post("/api/design-workshops", json={
        "title": "Bounded note", "notes": "x" * 4000,
    })
    assert ok.status_code == 201, "a real note — ten paragraphs — must still be accepted"

    patched = client.patch(
        f"/api/design-workshops/{ok.json()['id']}",
        json={"notes": "y" * (MAX_NOTES_CHARS + 1)},
    )
    assert patched.status_code == 422, "the update body is the same door"


async def test_a_never_read_client_merges_its_singleton_instead_of_replacing_it(client, workshop):
    """A client that never downloaded the stage must not delete the keys it never saw.

    THE FAILURE THIS PINS. A workshop is set up in the office with stage 1 complete. The designer
    opens that stage in a village, the download fails, and the form comes up blank — blank because
    unread, not because empty. Both clients say exactly that on screen and both promise that
    nothing left blank will overwrite an answer recorded elsewhere. They could not keep it: a
    singleton's `data` is replaced wholesale, so the one field typed in the courtyard deleted
    everything written in the office, in place, with no RecordRevision to recover it. The promoted
    columns went with it, so the workshop fell out of every "Ikat in Odisha" filter and the report
    cover handed to the visiting officer printed blank.

    `merge` is per ENTRY and defaults to false, so every client that has read the stage keeps the
    replace semantics it relies on — an absent key is a real deletion for them, and the second half
    of this test is what stops the fix over-reaching into that case.
    """
    path = f"/api/design-workshops/{workshop}/stages/WORKSHOP_SETUP"
    office = {"workshopTitle": "Bandha revival", "craftName": "Ikat", "clusterName": "Barpali"}
    client.put(path, json={"entries": [{"entityKey": "workshopSetup", "data": office}]})

    # The courtyard: one field, from a client that has never read the stage.
    merged = client.put(path, json={"entries": [{
        "entityKey": "workshopSetup",
        "data": {"venue": "Barpali weavers hall"},
        "merge": True,
    }]})
    assert merged.status_code == 200, merged.text

    stored = _setup_entry(client, workshop)
    assert stored["venue"] == "Barpali weavers hall", "the value typed in the field must land"
    assert stored["craftName"] == "Ikat", "a key the client never read must survive"
    assert stored["clusterName"] == "Barpali"
    assert stored["workshopTitle"] == "Bandha revival"

    # The promoted columns are read off the MERGED entry, so the header keeps its craft too — this
    # is the half that decides whether the workshop stays in the filters and on the report cover.
    header = client.get(f"/api/design-workshops/{workshop}").json()
    assert header["craftName"] == "Ikat", "the promoted column must not be nulled by a merge"

    # ...AND THE DEFAULT IS STILL A REPLACE. Without this, the fix would silently make every
    # deletion impossible: a client that HAS read the row means it when it omits a key.
    replaced = client.put(path, json={"entries": [{
        "entityKey": "workshopSetup",
        "data": {"workshopTitle": "Bandha revival", "craftName": "Ikat"},
    }]})
    assert replaced.status_code == 200, replaced.text
    after = _setup_entry(client, workshop)
    assert "clusterName" not in after or after["clusterName"] in (None, ""), \
        "an omitted key from a client that has read the row is still a deletion"
    assert after["craftName"] == "Ikat"
