import functools
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
    _sketches(client, workshop, [A, B])
    before = {r["name"]: r["_entryId"] for r in _rows(client, workshop)}

    _sketches(client, workshop, [A])
    response = _sketches(client, workshop, [A, B])

    assert response.status_code == 200, response.text
    assert response.json()["created"] == 0, "the row must be resurrected, not re-inserted"

    after = {r["name"]: r["_entryId"] for r in _rows(client, workshop)}
    assert set(after) == {"Runner", "Stole"}
    assert after["Stole"] == before["Stole"], "resurrecting must preserve the row's id"


async def test_an_already_deleted_row_is_not_reported_as_removed_again(client, workshop):
    _sketches(client, workshop, [A, B])
    assert _sketches(client, workshop, [A]).json()["removed"] == 1
    assert _sketches(client, workshop, [A]).json()["removed"] == 0


async def test_two_workshops_may_use_the_same_client_key(client):
    one = client.post("/api/design-workshops", json={"title": "One"}).json()["id"]
    two = client.post("/api/design-workshops", json={"title": "Two"}).json()["id"]
    assert _sketches(client, one, [A]).status_code == 200
    assert _sketches(client, two, [A]).status_code == 200


async def test_many_rows_without_a_client_key_coexist(client, workshop):
    rows = [{"sketchNo": f"W-{i}", "name": f"Web {i}", "image": "m1"} for i in range(4)]
    response = _sketches(client, workshop, rows)
    assert response.status_code == 200, response.text
    assert len(_rows(client, workshop)) == 4


async def test_replace_collections_false_leaves_other_rows_alone(client, workshop):
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
    assert to_plain(payload["singleton"]["concept"]) == "Revised concept"


async def test_a_soft_deleted_workshop_keeps_its_stage_data(client, workshop):
    _sketches(client, workshop, [A, B])
    assert client.delete(f"/api/design-workshops/{workshop}").status_code == 204
    assert client.post(f"/api/design-workshops/{workshop}/restore").status_code == 200
    assert len(_rows(client, workshop)) == 2


async def test_editing_a_deleted_workshop_is_refused(client, workshop):
    client.delete(f"/api/design-workshops/{workshop}")
    assert _sketches(client, workshop, [A]).status_code == 409
    client.post(f"/api/design-workshops/{workshop}/restore")


async def test_deleting_the_last_row_of_a_collection_actually_removes_it(client, workshop):
    _sketches(client, workshop, [A, B])
    assert len(_rows(client, workshop)) == 2

    result = _sketches(client, workshop, [], emptied=["sketch"]).json()
    assert result["removed"] == 2, "an emptied collection must be swept"
    assert _rows(client, workshop) == []


async def test_a_payload_that_never_named_an_entity_cannot_delete_it(client, workshop):
    path = f"/api/design-workshops/{workshop}/stages/COSTING_MARKET_LINKAGE"
    seeded = client.put(path, json={"entries": [
        {"entityKey": "costSheet", "data": {
            "_clientKey": "cs-1", "materialCost": "1650.00", "labourCost": "900.00",
            "expectedPrice": "3200.00"}},
        {"entityKey": "buyerLink", "data": {"_clientKey": "bl-1", "buyerName": "Utkalika"}},
    ]})
    assert seeded.status_code == 200, seeded.text
    assert seeded.json()["created"] == 2

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
    _sketches(client, workshop, [A, B])
    result = _sketches(client, workshop, [], replace=False, emptied=["sketch"]).json()
    assert result["removed"] == 0
    assert len(_rows(client, workshop)) == 2


async def test_an_empty_payload_never_sweeps_a_singleton(client, workshop):
    client.put(
        f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF",
        json={"entries": [{"entityKey": "designBrief", "data": {
            "concept": "Keep me", "targetCategories": ["TABLE_LINEN"]}}]},
    )
    client.put(f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF", json={"entries": []})

    payload = client.get(f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF").json()
    assert to_plain(payload["singleton"].get("concept")) == "Keep me"


async def test_a_workshop_titled_in_a_non_latin_script_can_still_be_exported(client):
    workshop_id = client.post(
        "/api/design-workshops", json={"title": "ସମ୍ବଲପୁରୀ ଇକତ କର୍ମଶାଳା"}
    ).json()["id"]

    for fmt in ("DOCX", "PDF"):
        response = client.post(
            f"/api/design-workshops/{workshop_id}/report", json={"formats": [fmt]}
        )
        assert response.status_code == 200, response.text
        disposition = response.headers["content-disposition"]
        assert disposition.isascii()
        assert "filename*=UTF-8''" in disposition


async def test_a_failed_export_records_no_phantom_row(client):
    workshop_id = client.post(
        "/api/design-workshops", json={"title": "कार्यशाला"}
    ).json()["id"]
    client.post(f"/api/design-workshops/{workshop_id}/report", json={"formats": ["DOCX"]})

    exports = client.get(f"/api/design-workshops/{workshop_id}/exports").json()
    assert len(exports) == 1
    assert exports[0]["checksumSha256"]


async def test_a_rejected_value_does_not_destroy_the_one_already_stored(client, workshop):
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
    path = f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE"
    response = client.put(path, json={"entries": [{"entityKey": "existingProduct", "data": {
        "_clientKey": "p-divergent",
        "name": "Saree",
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

    assert len(errors) == 1, "the scope count — what the web used to print"
    assert sum(len(fields) for fields in errors.values()) == 3
    assert body["refusedAnswers"] == 3

    clean = client.put(path, json={"entries": [{"entityKey": "existingProduct", "data": {
        "_clientKey": "p-clean", "name": "Stole", "price": "1200.00",
    }}]})
    assert clean.status_code == 200, clean.text
    assert clean.json()["errors"] == {}
    assert clean.json()["refusedAnswers"] == 0


async def test_a_stage_save_never_demotes_a_status_the_designer_set(client, workshop):
    path = f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE"
    client.patch(f"/api/design-workshops/{workshop}", json={"status": "COMPLETE"})
    client.put(path, json={"entries": [{"entityKey": "existingProduct", "data": {
        "name": "Saree", "price": "1.00"}}]})
    assert client.get(f"/api/design-workshops/{workshop}").json()["status"] == "COMPLETE"


async def test_a_draft_still_advances_on_its_first_save(client, workshop):
    assert client.get(f"/api/design-workshops/{workshop}").json()["status"] == "DRAFT"
    client.put(
        f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE",
        json={"entries": [{"entityKey": "existingProduct", "data": {
            "name": "Saree", "price": "1.00"}}]},
    )
    assert client.get(f"/api/design-workshops/{workshop}").json()["status"] == "IN_PROGRESS"


async def test_a_promoted_column_can_be_cleared_and_others_are_untouched(client, workshop):
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
    path = f"/api/design-workshops/{workshop}/stages/EXISTING_PRODUCTS_BASELINE"
    response = client.put(path, json={"entries": [
        {"entityKey": "existingProduct", "ordinal": 0,
         "data": {"_clientKey": "dup", "name": "A", "price": "1.00"}},
        {"entityKey": "existingProduct", "ordinal": 1,
         "data": {"_clientKey": "dup", "name": "B", "price": "2.00"}},
    ]})
    assert response.status_code == 200, response.text
    assert any("the same entry was sent twice" in k for k in response.json()["droppedKeys"])
    rows = client.get(path).json()["collections"]["existingProduct"]
    assert sorted(r["name"] for r in rows) == ["A", "B"], "neither row may be lost"


async def test_a_status_filter_the_enum_does_not_have_is_a_422_not_a_500(client):
    for bad in ("draft", "in_progress", "ALL", "NOPE"):
        response = client.get("/api/design-workshops", params={"statusFilter": bad})
        assert response.status_code == 422, f"{bad!r} -> {response.status_code} {response.text}"
        assert "DRAFT" in response.text, "the 422 must name the values a client can send"

    assert client.get(
        "/api/design-workshops", params={"statusFilter": "DRAFT"}
    ).status_code == 200


async def test_a_nul_byte_in_a_filter_is_a_result_set_and_not_a_server_error(client):
    for parameter in ("search", "craftName", "state"):
        response = client.get("/api/design-workshops", params={parameter: "\x00"})
        assert response.status_code == 200, f"{parameter} -> {response.status_code}"
        assert "items" in response.json()


async def test_a_workshop_note_is_bounded_like_every_field_beside_it(client):
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
    path = f"/api/design-workshops/{workshop}/stages/WORKSHOP_SETUP"
    office = {"workshopTitle": "Bandha revival", "craftName": "Ikat", "clusterName": "Barpali"}
    client.put(path, json={"entries": [{"entityKey": "workshopSetup", "data": office}]})

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

    header = client.get(f"/api/design-workshops/{workshop}").json()
    assert header["craftName"] == "Ikat", "the promoted column must not be nulled by a merge"

    replaced = client.put(path, json={"entries": [{
        "entityKey": "workshopSetup",
        "data": {"workshopTitle": "Bandha revival", "craftName": "Ikat"},
    }]})
    assert replaced.status_code == 200, replaced.text
    after = _setup_entry(client, workshop)
    assert "clusterName" not in after or after["clusterName"] in (None, ""), \
        "an omitted key from a client that has read the row is still a deletion"
    assert after["craftName"] == "Ikat"



async def test_an_entry_id_belonging_to_another_collection_is_refused_not_obeyed(client, workshop):
    path = f"/api/design-workshops/{workshop}/stages/TRADITIONAL_PROCESS_BASELINE"
    client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "data": {"_clientKey": "t1", "name": "Pit loom"}},
        {"entityKey": "rawMaterial", "ordinal": 0,
         "data": {"_clientKey": "m1", "name": "Tussar yarn"}},
    ]})
    before = client.get(path).json()["collections"]
    material_id = before["rawMaterial"][0]["_entryId"]

    crossed = client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "entryId": material_id,
         "data": {"_clientKey": "t1", "name": "Warping drum"}},
    ]})
    assert crossed.status_code == 200, crossed.text
    assert any("an entry from a different list" in k for k in crossed.json()["droppedKeys"]), \
        "the refusal has to be visible in the one channel both clients already render"

    after = client.get(path).json()["collections"]
    assert [r["name"] for r in after["rawMaterial"]] == ["Tussar yarn"], \
        "the raw material must not be overwritten with a tool's answers"
    assert [r["name"] for r in after["tool"]] == ["Warping drum"]


async def test_two_entries_sharing_one_entry_id_do_not_silently_overwrite_each_other(
    client, workshop
):
    path = f"/api/design-workshops/{workshop}/stages/TRADITIONAL_PROCESS_BASELINE"
    client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "data": {"_clientKey": "t1", "name": "Pit loom"}},
    ]})
    tool_id = client.get(path).json()["collections"]["tool"][0]["_entryId"]

    duplicated = client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "entryId": tool_id,
         "data": {"_clientKey": "t1", "name": "Pit loom"}},
        {"entityKey": "tool", "ordinal": 1, "entryId": tool_id,
         "data": {"_clientKey": "t2", "name": "Warping drum"}},
    ]})
    assert duplicated.status_code == 200, duplicated.text
    assert any("the same entry was sent twice" in k for k in duplicated.json()["droppedKeys"])

    rows = client.get(path).json()["collections"]["tool"]
    assert sorted(r["name"] for r in rows) == ["Pit loom", "Warping drum"], \
        "the second entry must become its own row, not eat the first one's"


async def test_an_entry_that_says_it_is_merging_never_arms_the_sweep(client, workshop):
    path = f"/api/design-workshops/{workshop}/stages/TRADITIONAL_PROCESS_BASELINE"
    client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0, "data": {"_clientKey": "t1", "name": "Pit loom"}},
        {"entityKey": "tool", "ordinal": 1, "data": {"_clientKey": "t2", "name": "Bobbin winder"}},
    ]})

    unread = client.put(path, json={"entries": [
        {"entityKey": "tool", "ordinal": 0,
         "data": {"_clientKey": "t3", "name": "Warping drum"}, "merge": True},
    ]})
    assert unread.status_code == 200, unread.text
    assert unread.json()["removed"] == 0, \
        "a client that has not read the collection must delete nothing"
    rows = client.get(path).json()["collections"]["tool"]
    assert sorted(r["name"] for r in rows) == ["Bobbin winder", "Pit loom", "Warping drum"]

    emptied = client.put(path, json={
        "entries": [{"entityKey": "tool", "ordinal": 0,
                     "data": {"_clientKey": "t3", "name": "Warping drum"}, "merge": True}],
        "emptiedEntities": ["tool"],
    })
    assert emptied.status_code == 200, emptied.text
    assert emptied.json()["removed"] == 2
    assert [r["name"] for r in client.get(path).json()["collections"]["tool"]] == ["Warping drum"]

    replaced = client.put(path, json={
        "entries": [{"entityKey": "tool", "ordinal": 0,
                     "data": {"_clientKey": "t4", "name": "Reed"}}],
    })
    assert replaced.json()["removed"] == 1



async def test_a_refused_submit_still_reports_everything_it_wrote(client, workshop):
    _sketches(client, workshop, [A, B])
    refused = client.put(
        f"/api/design-workshops/{workshop}/stages/SKETCH_DEVELOPMENT",
        json={
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
    assert [r["name"] for r in _rows(client, workshop)] == ["Runner"]


async def test_the_submit_message_names_the_kind_of_fault_it_found(client, workshop):
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
    saved = _sketches(client, workshop, [A]).json()
    assert "stored" not in saved
    assert set(saved) == {
        "stageKey", "saved", "created", "createdByEntity", "updated", "removed", "errors", "refusedAnswers",
        "droppedKeys", "droppedCustomKeys", "completeness", "transcriptionsQueued",
        "transcriptionConsentRefusal", "schemaVersion", "customSchemaVersion",
    }



async def test_the_create_forms_craft_is_backed_by_a_stage_entry(client):
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



class _AsDesigner:
    def __init__(self, client: Any, token: str, user_id: str = "", email: str = "") -> None:
        self._client = client
        self._headers = {"Authorization": f"Bearer {token}"}
        self.user_id = user_id
        self.email = email

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
    email = f"sync-designer-{uuid.uuid4().hex[:8]}@example.org"
    created = client.post("/api/users", json={
        "email": email, "name": "Sync Designer", "role": "DESIGNER",
        "password": "unused-password",
        "mustChangePassword": False,
    })
    assert created.status_code == 201, created.text
    return _AsDesigner(
        client,
        create_access_token(subject=created.json()["id"]),
        user_id=created.json()["id"],
        email=email,
    )


async def test_a_designer_editing_a_deleted_workshop_is_told_it_is_deleted(
    client, designer_client
):
    rostered = client.post("/api/designers/roster", json={
        "email": designer_client.email,
        "fullName": "Sync Designer",
        "institution": "Directorate of Handicrafts",
    })
    assert rostered.status_code in (201, 409), rostered.text
    mine = client.post("/api/design-workshops", json={"title": "Designer's own workshop"})
    assert mine.status_code == 201, mine.text
    workshop_id = mine.json()["id"]
    granted = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [designer_client.user_id]},
    )
    assert granted.status_code == 200, granted.text
    assert _sketches(designer_client, workshop_id, [A]).status_code == 200

    assert client.delete(f"/api/design-workshops/{workshop_id}").status_code == 204

    refused = _sketches(designer_client, workshop_id, [A])
    assert refused.status_code == 409, refused.text
    assert "deleted" in refused.json()["detail"].lower()

    assert designer_client.get(f"/api/design-workshops/{workshop_id}").status_code == 404

    client.post(f"/api/design-workshops/{workshop_id}/restore")


async def test_a_stranger_is_still_told_nothing_about_a_deleted_workshop(
    client, designer_client, workshop
):
    assert _sketches(designer_client, workshop, [A]).status_code == 404
    assert client.delete(f"/api/design-workshops/{workshop}").status_code == 204
    stranger = _sketches(designer_client, workshop, [A])
    assert stranger.status_code == 404, stranger.text
    assert stranger.json()["detail"] == "Record not found"
    client.post(f"/api/design-workshops/{workshop}/restore")



def test_the_reserved_key_differs_per_stage_because_custom_reuses_one_entity_key():
    from app.services.custom_sections import CUSTOM_ENTITY_KEY
    from app.services.design_workshops import singleton_client_key

    assert singleton_client_key("DESIGN_BRIEF") != singleton_client_key("WORKSHOP_OUTCOMES")
    assert singleton_client_key("DESIGN_BRIEF") == singleton_client_key("DESIGN_BRIEF")
    assert singleton_client_key("DESIGN_BRIEF").startswith("__")
    assert CUSTOM_ENTITY_KEY.startswith("_"), (
        "this test's premise is that `_custom` is one entity key shared by every stage"
    )


def _brief(client, workshop_id, concept):
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/DESIGN_BRIEF",
        json={"entries": [{"entityKey": "designBrief", "data": {
            "concept": concept, "targetCategories": ["TABLE_LINEN"]}}]},
    )



def _entry_rows(client, workshop_id, entity_key):
    return client.portal.call(functools.partial(
        db.dwstageentry.find_many,
        where={"designWorkshopId": workshop_id, "entityKey": entity_key},
    ))


def _brief_rows(client, workshop_id):
    return _entry_rows(client, workshop_id, "designBrief")


def _write_entry_row(client, data):
    return client.portal.call(functools.partial(db.dwstageentry.create, data=data))


def test_a_singleton_row_carries_the_reserved_client_key(client, workshop):
    from app.services.design_workshops import singleton_client_key

    assert _brief(client, workshop, "First concept").status_code == 200
    rows = _brief_rows(client, workshop)
    assert len(rows) == 1
    assert rows[0].clientKey == singleton_client_key("DESIGN_BRIEF"), (
        "without a non-null clientKey the unique index cannot see this row, and two designers "
        "saving at once get two rows the read paths choose between at random"
    )


def test_the_prefill_seeded_singleton_carries_the_reserved_key_too(client):
    from app.services.design_workshops import singleton_client_key

    response = client.post(
        "/api/design-workshops",
        json={"title": "Prefilled workshop", "craftName": "Blue pottery"},
    )
    assert response.status_code == 201, response.text
    workshop_id = response.json()["id"]

    rows = _entry_rows(client, workshop_id, "workshopSetup")
    assert len(rows) == 1, (
        f"the create seeded {len(rows)} workshopSetup rows; this test's premise is that it seeds one"
    )
    assert rows[0].clientKey == singleton_client_key(rows[0].stageKey), (
        "a seeded singleton with a null clientKey is invisible to the unique index, so the "
        "guarantee the schema states — one row per (workshop, entity) — is not enforced on the "
        "entity that carries the workshop's craft, cluster, state, district and dates"
    )


def test_a_singleton_written_before_the_reserved_key_adopts_it_on_the_next_save(
    client, workshop
):
    from app.services import design_workshops as dw

    legacy = _write_entry_row(client, {
        "designWorkshopId": workshop,
        "stageKey": "DESIGN_BRIEF",
        "entityKey": "designBrief",
        "ordinal": 0,
        "data": dw._json({"concept": "Written before the key existed"}),
    })
    assert legacy.clientKey is None, "this test's premise is a row with no client key"

    assert _brief(client, workshop, "Edited today").status_code == 200

    rows = _brief_rows(client, workshop)
    assert len(rows) == 1, f"the save split the singleton into {len(rows)} rows"
    assert rows[0].id == legacy.id, "the existing row must be updated, not replaced"
    assert rows[0].clientKey == dw.singleton_client_key("DESIGN_BRIEF"), (
        "the row is still invisible to the unique index, so this workshop's singleton can still "
        "be duplicated by two designers saving at once"
    )


def test_two_concurrent_saves_of_one_singleton_leave_one_row(
    client, workshop, monkeypatch
):
    from app.services import design_workshops as dw

    original = dw.hydrate_entries
    injected: list[str] = []

    async def hydrate_then_lose_the_race(entries, **kwargs):
        await original(entries, **kwargs)
        if injected:
            return
        row = await db.dwstageentry.create(data={
            "designWorkshopId": workshop,
            "stageKey": "DESIGN_BRIEF",
            "entityKey": "designBrief",
            "ordinal": 0,
            "data": dw._json({"concept": "The other designer's concept"}),
            "clientKey": dw.singleton_client_key("DESIGN_BRIEF"),
            "createdById": None,
        })
        injected.append(row.id)

    monkeypatch.setattr(dw, "hydrate_entries", hydrate_then_lose_the_race)

    response = _brief(client, workshop, "My concept")
    assert response.status_code == 200, response.text
    assert injected, "the competing row was never inserted; this test proved nothing"

    body = response.json()
    assert body["created"] == 0, "the INSERT should have been absorbed into an UPDATE"
    assert body["updated"] == 1

    rows = _brief_rows(client, workshop)
    assert len(rows) == 1, (
        f"the singleton split into {len(rows)} rows; the unique index did not refuse the second "
        "INSERT, which is the entire defect the reserved client key exists to close"
    )
    assert rows[0].id == injected[0], "the loser must write into the winner's row, not its own"
    assert to_plain(rows[0].data["concept"]) == "My concept"
    assert rows[0].createdById is None, (
        "the row was created by the other designer's save; crediting this request with it would "
        "put a wrong author beside every field it did not set"
    )


def test_two_entries_for_one_singleton_in_one_payload_make_one_row(client, workshop):
    response = client.put(
        f"/api/design-workshops/{workshop}/stages/DESIGN_BRIEF",
        json={"entries": [
            {"entityKey": "designBrief", "data": {
                "concept": "First half", "targetCategories": ["TABLE_LINEN"]}},
            {"entityKey": "designBrief", "data": {"concept": "Second half"}},
        ]},
    )
    assert response.status_code == 200, response.text

    rows = _brief_rows(client, workshop)
    assert len(rows) == 1, f"one singleton, {len(rows)} rows"
    assert to_plain(rows[0].data["concept"]) == "Second half", "the later entry wins its own keys"
    assert rows[0].data["targetCategories"] == ["TABLE_LINEN"], (
        "the earlier entry's keys must survive where the later one is silent"
    )
    assert any("were combined" in key for key in response.json()["droppedKeys"]), (
        "a client sending two entries for one singleton is a bug, and the response is where it "
        "becomes findable"
    )


_MOTIF_IDS = [f"cm{i:023d}" for i in range(20)]


def _background(client, workshop_id):
    return client.get(
        f"/api/design-workshops/{workshop_id}/stages/CLUSTER_CRAFT_BACKGROUND"
    ).json()


async def test_a_gallery_below_its_floor_saves_and_is_reported_incomplete(client, workshop):
    response = client.put(
        f"/api/design-workshops/{workshop}/stages/CLUSTER_CRAFT_BACKGROUND",
        json={"entries": [
            {"entityKey": "clusterBackground", "data": {"motifPhotos": _MOTIF_IDS}}
        ]},
    )
    assert response.status_code == 200, response.text

    stored = _background(client, workshop)["singleton"]["motifPhotos"]
    assert stored == _MOTIF_IDS, f"stored {len(stored)} of {len(_MOTIF_IDS)} photographs"

    completeness = response.json()["completeness"]
    assert completeness["isComplete"] is False
    assert "Traditional motif photographs (20 of 25)" in completeness["missing"], (
        f"the shortfall is not named in {completeness['missing']}"
    )


async def test_a_submit_does_not_revert_a_gallery_below_its_floor(client, workshop):
    path = f"/api/design-workshops/{workshop}/stages/CLUSTER_CRAFT_BACKGROUND"
    first = client.put(path, json={"entries": [
        {"entityKey": "clusterBackground", "data": {"motifPhotos": _MOTIF_IDS[:5]}}
    ]})
    assert first.status_code == 200, first.text

    second = client.put(path, json={
        "entries": [{"entityKey": "clusterBackground", "data": {"motifPhotos": _MOTIF_IDS}}],
        "submit": True,
    })
    assert second.status_code in (200, 422), second.text
    body = second.json()
    errors = body.get("detail", body).get("errors", {})
    assert "motifPhotos" not in errors.get("clusterBackground", {}), (
        f"the gallery was refused on submit: {errors}"
    )

    stored = _background(client, workshop)["singleton"]["motifPhotos"]
    assert stored == _MOTIF_IDS, (
        f"the submit left {len(stored)} photographs on the record instead of {len(_MOTIF_IDS)} — "
        "the gallery was reverted to what the first save stored"
    )
