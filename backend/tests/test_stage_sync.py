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
from typing import Any

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


async def test_the_response_counts_refused_answers_and_not_the_rows_they_sat_in(client, workshop):
    """ONE number for "how many answers were refused", so two surfaces cannot each derive their own.

    ``errors`` is ``{scope: {field: message}}`` and carried no total, so both clients computed one and
    they computed different things off the same body: the web read ``Object.keys(errors).length`` —
    the number of SCOPES — while Android built one refusal per (scope, field) pair and counted FIELDS.
    Both then printed their number in the same sentence, with the same word: "The server refused N of
    your answers". A designer who saved this exact entry was told "1 answer" on a laptop and
    "3 answers" on the phone, and neither surface was lying about what it had counted.

    THIS ENTRY IS THE DIVERGENCE, WHICH IS WHY THE ASSERTIONS NAME BOTH READINGS. One row of one
    collection with three unreadable numbers in it: scopes = 1, fields = 3. A test that refused a
    single field would pass for either reading and prove nothing at all — which is how this survived.

    Fields is the right reading: an answer is what somebody typed into one box, a scope is a row of
    the form, and the remedy the sentence offers ("open the stage to see which fields are marked") is
    per-field too. ``refusedAnswers`` is now the server's own count of it.
    """
    path = f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE"
    response = client.put(path, json={"entries": [{"entityKey": "existingProduct", "data": {
        "_clientKey": "p-divergent",
        "name": "Saree",
        # Three boxes a designer fat-fingered, all in the one row.
        "price": "65OO",
        "lengthCm": "one hundred",
        "monthlyCapacity": "a dozen",
    }}]})
    assert response.status_code == 200, response.text
    body = response.json()

    errors = body["errors"]
    assert len(errors) == 1, f"expected one scope, got {sorted(errors)}"
    scope = next(iter(errors))
    assert set(errors[scope]) == {"price", "lengthCm", "monthlyCapacity"}

    # The two readings, spelled out, and the server siding with the one a designer means.
    assert len(errors) == 1, "the scope count — what the web used to print"
    assert sum(len(fields) for fields in errors.values()) == 3
    assert body["refusedAnswers"] == 3

    # And it is 0 rather than absent on a save that refused nothing, so a client can render it
    # unconditionally instead of guessing what a missing key meant.
    clean = client.put(path, json={"entries": [{"entityKey": "existingProduct", "data": {
        "_clientKey": "p-clean", "name": "Stole", "price": "1200.00",
    }}]})
    assert clean.status_code == 200, clean.text
    assert clean.json()["errors"] == {}
    assert clean.json()["refusedAnswers"] == 0


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


# --------------------------------------------------------------------------------------
# Which ROW a payload addresses, and which rows it is allowed to delete
#
# Every failure in this block is a row of somebody's fieldwork disappearing or being overwritten
# under an HTTP 200. They sit together because two of the three share one cause: `save_stage`'s
# three-way matcher carried a guard on two of its arms and not on the third.
#
# ── THE ROW ID GOES IN `entryId`, AT THE TOP LEVEL OF THE ENTRY, NEVER IN `data["_entryId"]` ──
#
# The two tests below were first written putting the id inside `data`, which is where the READ
# puts it (`_stages_payload` injects `_entryId` into every serialised row) — so it looks like the
# obvious shape and it is the wrong one. `StageEntryIn` declares `entryId` as a field of its own,
# `save_stage` matches on `entry.entryId`, and both shipped clients LIFT the key out of the row
# before sending: `frontend/lib/designWorkshopStore.ts:3057` (`entryId: row._entryId`) and
# `android/…/WorkshopSync.kt:1952` (`entryId = row.values["_entryId"]`).
#
# Left inside `data` the id is simply one more underscore-prefixed protocol key: `save_stage`
# excludes those from `droppedKeys` deliberately, so it is not reported either, and the entry
# falls through to the `_clientKey` arm as though no id had been sent at all. Both tests then
# assert against a code path that never ran — they were RED against a fix that was correct, which
# is the worst of the three possible outcomes because it reads as a defect in the server.
#
# Do not "simplify" these payloads back to the shape the GET returns. A test that sends the id
# where nothing reads it pins nothing, and this block is the last guard between a bulk import and
# one row of a designer's answers being written into another entity's row.
# --------------------------------------------------------------------------------------


async def test_an_entry_id_belonging_to_another_collection_is_refused_not_obeyed(client, workshop):
    """THE REGRESSION. `by_id` is keyed by row id ALONE, across every entity of the stage.

    The clientKey arm is keyed by `(entityKey, clientKey)` and the singleton arm filters on
    `r.entityKey == entity.key`; the entryId arm had no entity test, and nothing downstream
    recovered — the UPDATE writes `data`, `ordinal` and `deletedAt` and never `entityKey`. So a
    `tool` entry carrying a `rawMaterial` row's id rewrote the raw material's answers with a
    tool's while it stayed a raw material, `validate_entry` could not object (it validated against
    the entity the PAYLOAD named), and the real tool row — named by nothing in `touched_ids` —
    was soft-deleted by the sweep. One 200 reading `saved: 1, removed: 1`, and the corrupted row
    then prints in a .docx table with another entity's fields in it.
    """
    path = f"/api/design-workshops/{workshop}/stages/TRADITIONAL_PROCESS_BASELINE"
    client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "data": {"_clientKey": "t1", "name": "Pit loom"}},
        {"entityKey": "rawMaterial", "ordinal": 0,
         "data": {"_clientKey": "m1", "name": "Tussar yarn"}},
    ]})
    before = client.get(path).json()["collections"]
    material_id = before["rawMaterial"][0]["_entryId"]

    # `entryId` at the TOP LEVEL of the entry — the shape both clients send. See the block comment
    # above for why putting it in `data` exercises nothing at all.
    crossed = client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "entryId": material_id,
         "data": {"_clientKey": "t1", "name": "Warping drum"}},
    ]})
    assert crossed.status_code == 200, crossed.text
    assert any("belongs to rawMaterial" in k for k in crossed.json()["droppedKeys"]), \
        "the refusal has to be visible in the one channel both clients already render"

    after = client.get(path).json()["collections"]
    assert [r["name"] for r in after["rawMaterial"]] == ["Tussar yarn"], \
        "the raw material must not be overwritten with a tool's answers"
    # The tool falls through to its own client key and is UPDATED, not duplicated and not lost.
    assert [r["name"] for r in after["tool"]] == ["Warping drum"]


async def test_two_entries_sharing_one_entry_id_do_not_silently_overwrite_each_other(
    client, workshop
):
    """The mirror of the duplicate-clientKey guard, on the key that has no unique index.

    Both entries resolved through `by_id` to one row, both became update tuples, and the
    transaction applied them in order so the SECOND entry's data won wholesale: one row destroyed,
    `saved: 2` reported, nothing in `droppedKeys` and nothing in `errors`. The path is ordinary the
    moment anything copies a row's `data` to make a second row — `_entryId` is put INTO that data
    by the stage read and by `assemble_workshop_data`.
    """
    path = f"/api/design-workshops/{workshop}/stages/TRADITIONAL_PROCESS_BASELINE"
    client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "data": {"_clientKey": "t1", "name": "Pit loom"}},
    ]})
    tool_id = client.get(path).json()["collections"]["tool"][0]["_entryId"]

    # Both entries name the SAME row id, in the field the matcher actually reads.
    duplicated = client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "entryId": tool_id,
         "data": {"_clientKey": "t1", "name": "Pit loom"}},
        {"entityKey": "tool", "ordinal": 1, "entryId": tool_id,
         "data": {"_clientKey": "t2", "name": "Warping drum"}},
    ]})
    assert duplicated.status_code == 200, duplicated.text
    assert any("duplicate in payload" in k for k in duplicated.json()["droppedKeys"])

    rows = client.get(path).json()["collections"]["tool"]
    assert sorted(r["name"] for r in rows) == ["Pit loom", "Warping drum"], \
        "the second entry must become its own row, not eat the first one's"


async def test_an_entry_that_says_it_is_merging_never_arms_the_sweep(client, workshop):
    """THE SERVER SIDE OF THE BLOCKER OF 2026-08-13, which was closed on the client only.

    `merge: true` means, in `StageEntryIn`'s own words, "I am sending every key I HAVE, not every
    key there IS" — set when the client knows it has not seen the server's copy.
    `replaceCollections` defaults to TRUE when absent and soft-deletes every row the payload does
    not name. A payload can assert both, and the server obeyed the destructive one: three rows the
    phone had never downloaded went, under a 200 reporting `removed: 3`.

    The handset was fixed by removing a kotlinx default. The contradiction that turned it into row
    deletion was not, and it is reachable from an older build, a script, or any direct caller.
    """
    path = f"/api/design-workshops/{workshop}/stages/TRADITIONAL_PROCESS_BASELINE"
    client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "data": {"_clientKey": "t1", "name": "Pit loom"}},
        {"entityKey": "tool", "ordinal": 1, "data": {"_clientKey": "t2", "name": "Bobbin winder"}},
    ]})

    # `replaceCollections` deliberately OMITTED, exactly as the shipped handset sent it.
    unread = client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0,
         "data": {"_clientKey": "t3", "name": "Warping drum"}, "merge": True},
    ]})
    assert unread.status_code == 200, unread.text
    assert unread.json()["removed"] == 0, \
        "a client that has not read the collection must delete nothing"
    rows = client.get(path).json()["collections"]["tool"]
    assert sorted(r["name"] for r in rows) == ["Bobbin winder", "Pit loom", "Warping drum"]

    # AND THE EXPLICIT STATEMENT STILL WINS. `emptiedEntities` is not an inference drawn from
    # silence; it is the only way a client can delete the last row of a collection, there being no
    # per-row delete endpoint. Merging must not disarm it.
    emptied = client.put(path, json={
        "entries": [{"entityKey": "tool", "ordinal": 0,
                     "data": {"_clientKey": "t3", "name": "Warping drum"}, "merge": True}],
        "emptiedEntities": ["tool"],
    })
    assert emptied.status_code == 200, emptied.text
    assert emptied.json()["removed"] == 2
    assert [r["name"] for r in client.get(path).json()["collections"]["tool"]] == ["Warping drum"]

    # ...and the ordinary replace is untouched, or the fix would make deletion impossible.
    replaced = client.put(path, json={
        "entries": [{"entityKey": "tool", "ordinal": 0,
                     "data": {"_clientKey": "t4", "name": "Reed"}}],
    })
    assert replaced.json()["removed"] == 1


# --------------------------------------------------------------------------------------
# What a refusal tells the designer
# --------------------------------------------------------------------------------------


async def test_a_refused_submit_still_reports_everything_it_wrote(client, workshop):
    """`save_stage` COMMITS before the route looks at `errors`, so a 422 has already mutated.

    The refusal used to carry `{message, errors}` alone, throwing away `removed`, `created`,
    `updated`, `droppedKeys`, `completeness` and the rest — leaving every client with the
    reasonable and WRONG reading that nothing was written. The deleted row had gone and the
    workshop had left DRAFT, and the designer was told only "Some required fields are missing".
    """
    _sketches(client, workshop, [A, B])
    refused = client.put(
        f"/api/design-workshops/{workshop}/stages/SKETCH_DEVELOPMENT",
        json={
            # `image` is a required Basic field and is deliberately absent, which is what makes
            # this a strict-pass refusal at all; `unknownKey` is here to prove `droppedKeys`
            # survives the 422 too.
            "entries": [{"entityKey": "sketch", "ordinal": 0,
                         "data": {"_clientKey": "sk-a", "sketchNo": "SK-01",
                                  "name": "Runner", "unknownKey": "x"}}],
            "replaceCollections": True,
            "submit": True,
        },
    )
    assert refused.status_code == 422, refused.text
    detail = refused.json()["detail"]
    assert detail["removed"] == 1, "the sweep landed and the body has to say so"
    assert detail["updated"] == 1
    assert "sketch.unknownKey" in detail["droppedKeys"]
    assert detail["completeness"]["stageKey"] == "SKETCH_DEVELOPMENT"
    assert detail["errors"], "the per-field marks a strict pass exists to produce still travel"
    assert detail["refusedAnswers"] >= 1
    assert detail["message"]
    # The write really did land, which is the fact the old body hid.
    assert [r["name"] for r in _rows(client, workshop)] == ["Runner"]


async def test_the_submit_message_names_the_kind_of_fault_it_found(client, workshop):
    """One hard-coded sentence reported a fat-fingered decimal as a missing required field.

    `errors` mixes `validate_entry`'s "… is required" with `coerce_value`'s "… is not a valid
    number", and the strict pass called every one of them a missing required field — sending the
    designer to the empty boxes rather than to the wrong one.
    """
    path = f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE"
    unreadable = client.put(path, json={
        "entries": [{"entityKey": "existingProduct", "ordinal": 0,
                     "data": {"_clientKey": "p1", "name": "Stole", "price": "65OO"}}],
        "submit": True,
    })
    assert unreadable.status_code == 422, unreadable.text
    assert unreadable.json()["detail"]["message"] == "Some answers could not be read"

    missing = client.put(f"/api/design-workshops/{workshop}/stages/WORKSHOP_SETUP", json={
        "entries": [{"entityKey": "workshopSetup", "data": {"venue": "Barpali weavers hall"}}],
        "submit": True,
    })
    assert missing.status_code == 422, missing.text
    assert missing.json()["detail"]["message"] == "Some required fields are missing"


async def test_the_save_response_carries_no_stored_echo_block(client, workshop):
    """THE WIRE CONTRACT, PINNED BECAUSE THREE DOCSTRINGS ONCE DESCRIBED A FIELD THAT WAS NOT ON IT.

    `save_stage` built a `stored` dict for every non-`_custom` entry of every save — two write
    sites, no read site — and dropped it at the return, while its own docstring, the comments
    beside both write sites and the route's docstring all said it was "echoed back to the client".
    Android had already MEASURED the absence and written it down as fact in `DwStageRefusal.kt`,
    and built its three-state `DwHeld.UNRECORDED` around the gap.

    This test cannot fail against the old code — the field was never on the wire, which was the
    defect — so it is a contract pin rather than a regression: the deliverable for that finding is
    the corrected prose. Its job is to fail the day somebody adds `stored` to a docstring again
    without adding it to the response, or adds it to the response without saying so here.
    """
    saved = _sketches(client, workshop, [A]).json()
    assert "stored" not in saved
    assert set(saved) == {
        "stageKey", "saved", "created", "updated", "removed", "errors", "refusedAnswers",
        "droppedKeys", "droppedCustomKeys", "completeness", "transcriptionsQueued",
        "transcriptionConsentRefusal", "schemaVersion", "customSchemaVersion",
    }


# --------------------------------------------------------------------------------------
# The create form's own answers
# --------------------------------------------------------------------------------------


async def test_the_create_forms_craft_is_backed_by_a_stage_entry(client):
    """THE REGRESSION: the create wrote promoted COLUMNS that no stage entry backed.

    `POST /design-workshops` copies craft, cluster, state, district and the dates onto the header,
    but every one of those columns is declared in `PROMOTED_COLUMNS` under `workshopSetup.*`, whose
    single writer is supposed to be the stage entry. With nothing behind them, stage 1 opened with
    those boxes BLANK — nothing seeded them from the header — and the first save nulled all six
    (and the four beside them) under a 200 reading "Stage saved". The workshop then fell out of
    every list filter and search on craft, state, district and date, and showed "—" in the list's
    own columns, for the whole fortnight of capture.

    The walk asserted here is the designer's real one: create, OPEN the stage, add the venue, save.
    A payload that deliberately omits craftName is a designer CLEARING the box and must still clear
    it — `test_a_promoted_column_can_be_cleared_and_others_are_untouched` pins that and is left
    alone — so the fix is that the box is no longer empty when the form opens.
    """
    created = client.post("/api/design-workshops", json={
        "title": "Seeded workshop", "craftName": "Ikat", "clusterName": "Barpali",
        "state": "Odisha", "district": "Bargarh",
        "startDate": "2026-03-02", "endDate": "2026-03-15",
    })
    assert created.status_code == 201, created.text
    workshop_id = created.json()["id"]

    path = f"/api/design-workshops/{workshop_id}/stages/WORKSHOP_SETUP"
    opened = client.get(path).json()["singleton"]
    assert opened["craftName"] == "Ikat", "the form must open showing what the create form asked"
    assert opened["clusterName"] == "Barpali"
    assert opened["state"] == "Odisha"
    assert opened["district"] == "Bargarh"
    assert opened["startDate"] == "2026-03-02"
    assert opened["endDate"] == "2026-03-15"

    typed = {k: v for k, v in opened.items() if not k.startswith("_")}
    typed["venue"] = "Barpali weavers hall"
    saved = client.put(path, json={"entries": [
        {"entityKey": "workshopSetup", "data": typed},
    ]})
    assert saved.status_code == 200, saved.text

    header = client.get(f"/api/design-workshops/{workshop_id}").json()
    assert header["craftName"] == "Ikat", "the first stage-1 save used to null this"
    assert header["clusterName"] == "Barpali"
    assert header["state"] == "Odisha"
    assert header["district"] == "Bargarh"
    assert str(header["startDate"]).startswith("2026-03-02")


# --------------------------------------------------------------------------------------
# What a designer is told about a workshop an admin deleted
# --------------------------------------------------------------------------------------


class _AsDesigner:
    """The module's ONE ``TestClient``, wearing a different account's token.

    A test in this file reads "as the designer" without a second client existing anywhere — see
    :func:`designer_client` for the 500 that a second ``TestClient`` caused and why no amount of
    avoiding its lifespan would have helped. The same idiom, for the same reason, is
    ``tests/test_media_processing_jobs.py:134``.

    The header is merged per call rather than set on the client, so the admin's own requests through
    the same object are untouched — which matters here, because these tests interleave the two
    accounts deliberately (the designer creates and edits; the ADMIN does the soft delete).
    """

    def __init__(self, client: Any, token: str) -> None:
        self._client = client
        self._headers = {"Authorization": f"Bearer {token}"}

    def _send(self, method: str, url: str, **kwargs: Any) -> Any:
        headers = {**self._headers, **(kwargs.pop("headers", None) or {})}
        return getattr(self._client, method)(url, headers=headers, **kwargs)

    def get(self, url: str, **kwargs: Any) -> Any:
        return self._send("get", url, **kwargs)

    def post(self, url: str, **kwargs: Any) -> Any:
        return self._send("post", url, **kwargs)

    def put(self, url: str, **kwargs: Any) -> Any:
        return self._send("put", url, **kwargs)

    def patch(self, url: str, **kwargs: Any) -> Any:
        return self._send("patch", url, **kwargs)

    def delete(self, url: str, **kwargs: Any) -> Any:
        return self._send("delete", url, **kwargs)


@pytest.fixture(scope="module")
def designer_client(client):
    """A second signed-in client, a DESIGNER rather than an admin.

    The rest of this module runs as an ADMIN, which is exactly why the defect below survived: the
    409 was reachable for an admin and for nobody else, and an admin is not who owns unsent stages.

    **IT IS NOT A SECOND ``TestClient``, AND THAT IS THE WHOLE OF IT.** This fixture was written as
    ``second = TestClient(app)`` on the reasoning — spelled out at length, and wrong — that a bare
    ``TestClient`` with no ``with`` "issues requests against that same running app without running
    startup a second time". It does avoid the second lifespan. What it does NOT avoid is a second
    event loop: ``TestClient`` opens its own anyio portal **per request** whether or not a lifespan
    ran, so the first ``designer_client.post`` reached Prisma's HTTP session from a loop that was not
    the one ``db.connect()`` bound it to, and the route died in ``asyncio.locks.Event._get_loop``
    with *"is bound to a different event loop"*. The app answered **500 on ``POST
    /api/design-workshops``** and the two tests below failed on their own first line, reading like a
    defect in workshop creation rather than a fixture that had smuggled in a second loop.

    So there is exactly one ``TestClient`` in this module, and a request "as the designer" is that
    same client with a different ``Authorization`` header — the ``_As`` idiom
    ``tests/test_media_processing_jobs.py:134`` already uses for the same reason. One client, one
    portal, one loop, one Prisma connection.

    A second lifespan would have been worse still: it would ``db.disconnect()`` the connection the
    app is still using the moment it closed, and every later test in the file would fail somewhere
    inside Prisma with nothing pointing back here. Neither trap is available now.

    **THE DESIGNER IS CREATED OVER HTTP, AND THIS FIXTURE IS SYNCHRONOUS FOR THAT REASON.** It was
    written as ``async def`` calling ``await db.user.create(...)`` directly, and it could not work:
    ``client`` hands the app to ``TestClient``, which runs the lifespan — and therefore
    ``db.connect()`` — inside its OWN portal event loop, while an async fixture body runs in the
    anyio loop this module's ``anyio_backend`` provides. Prisma's HTTP session is bound to the loop
    that opened it, so the ``create`` died in ``asyncio.locks.Event._get_loop`` with "is bound to a
    different event loop" and took BOTH tests below down as setup ERRORs — a fixture failure, which
    reads nothing like the 404/409 regression they exist to pin.

    ``client``'s own user creation escapes this only because it happens BEFORE ``TestClient(app)``
    exists, around an explicit ``db.connect()``/``db.disconnect()`` pair in its own loop. There is
    no second such window here, so this goes through ``POST /api/users`` instead: the request runs
    in the portal loop that owns the connection, needs no ``await``, and touches ``db`` not at all.
    Do not convert this back to ``async def`` — the direct row write is what broke it.
    """
    email = f"sync-designer-{uuid.uuid4().hex[:8]}@example.org"
    created = client.post("/api/users", json={
        "email": email, "name": "Sync Designer", "role": "DESIGNER",
        # Never used: the token below is minted directly, as it is for the admin above. The field
        # is required by ``UserCreate`` and bounded at 8 characters, so it is spelled to say so.
        "password": "unused-password",
    })
    assert created.status_code == 201, created.text
    return _AsDesigner(
        client, create_access_token(subject=created.json()["id"])
    )


async def test_a_designer_editing_a_deleted_workshop_is_told_it_is_deleted(
    client, designer_client
):
    """THE REGRESSION: the 409 was dead code for the only people who could reach the condition.

    `load_workshop_or_404` refused a soft-deleted workshop with a 404 for every non-admin BEFORE
    the `for_edit` branch could answer 409 — and the accounts that reach this helper with
    `for_edit=True` are designers, who are not admins.

    What that cost is on the client. `designWorkshopStore` rethrows 409 out of the stage arm
    precisely so the workshop-level catch can print "Ask an admin to restore it, then sync again";
    a 404 is not rethrown, so an admin soft-deleting a duplicate while a designer's laptop held
    unsent stages stamped EVERY one of them permanent with "it will keep being refused until the
    answer that caused it is corrected — this is not a connection problem". One red line per stage,
    sending the designer to audit answers nothing had objected to.
    """
    mine = designer_client.post(
        "/api/design-workshops", json={"title": "Designer's own workshop"}
    )
    assert mine.status_code == 201, mine.text
    workshop_id = mine.json()["id"]
    assert _sketches(designer_client, workshop_id, [A]).status_code == 200

    assert client.delete(f"/api/design-workshops/{workshop_id}").status_code == 204

    refused = _sketches(designer_client, workshop_id, [A])
    assert refused.status_code == 409, refused.text
    assert "deleted" in refused.json()["detail"].lower()

    # READING IT IS STILL A 404, and that half must not move: the same 404 covers a REVOKED viewer
    # grant, and answering anything else there would confirm the id exists to somebody who has just
    # been turned away.
    assert designer_client.get(f"/api/design-workshops/{workshop_id}").status_code == 404

    client.post(f"/api/design-workshops/{workshop_id}/restore")


async def test_a_stranger_is_still_told_nothing_about_a_deleted_workshop(
    client, designer_client, workshop
):
    """The disclosure half of the swap above, asserted rather than argued.

    The ordering change moved the deleted test AFTER the who-may-enter test, so the 409 is only
    ever reachable by an account that has already proved it is the creator, an admin or a grantee.
    A designer with no claim on this workshop gets the same 404 with the same detail string
    whether it is deleted or merely somebody else's.
    """
    assert _sketches(designer_client, workshop, [A]).status_code == 404
    assert client.delete(f"/api/design-workshops/{workshop}").status_code == 204
    stranger = _sketches(designer_client, workshop, [A])
    assert stranger.status_code == 404, stranger.text
    assert stranger.json()["detail"] == "Record not found"
    client.post(f"/api/design-workshops/{workshop}/restore")
