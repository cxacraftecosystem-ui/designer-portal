import os
import uuid
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import design_workshops as dw, rich_text

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [pytest.mark.anyio]

STAGE_3 = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
STAGE_5 = "TRADITIONAL_PROCESS_BASELINE"
STAGE_6 = "EXISTING_PRODUCTS_BASELINE"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    if not _LOCAL:
        pytest.skip("needs a LOCAL database; refuses to run against a remote DATABASE_URL")
    tag = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        user = await db.user.create(data={
            "email": f"ref-test-{tag}@example.org", "name": "Ref Test", "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        craft = await db.craft.create(data={
            "name": f"Ikat weaving {tag}", "localName": "Bandha", "createdById": user.id,
        })
        workshop = await db.workshop.create(data={
            "title": f"Cluster workshop {tag}", "date": "2026-02-01T00:00:00Z",
            "place": "Bargarh", "createdById": user.id,
        })
        location = await db.location.create(data={
            "latitude": 21.33, "longitude": 83.62, "village": "Barpali",
            "state": "Odisha", "district": "Bargarh",
        })

        latha = await db.artisan.create(data={
            "name": f"Latha Devi {tag}", "localName": "ଲତା", "gender": "Female",
            "phone": "9876500001", "place": "Barpali", "createdById": user.id,
            "craftId": craft.id, "locationId": location.id, "workshopId": workshop.id,
            "extraMetadata": _json({"experienceYears": 22}),
        })
        mohan = await db.artisan.create(data={
            "name": f"Mohan Meher {tag}", "place": "Sonepur", "createdById": user.id,
            "craftId": craft.id,
        })
        await db.workshopartisan.create(
            data={"workshopId": workshop.id, "artisanId": mohan.id}
        )
        outsider = await db.artisan.create(data={
            "name": f"Sita Bai {tag}", "place": "Kutch", "createdById": user.id,
        })

        await db.mediafile.create(data={
            "originalFilename": "latha.jpg", "mediaType": "IMAGE", "mimeType": "image/jpeg",
            "sizeBytes": 1024, "bucket": "test", "objectKey": f"artisans/{tag}/latha.jpg",
            "uploadedById": user.id, "artisanId": latha.id,
        })
        for index in range(12):
            await db.mediafile.create(data={
                "originalFilename": f"latha-{index}.jpg", "mediaType": "IMAGE",
                "mimeType": "image/jpeg", "sizeBytes": 1024, "bucket": "test",
                "objectKey": f"artisans/{tag}/latha-extra-{index}.jpg",
                "uploadedById": user.id, "artisanId": latha.id,
            })
        await db.mediafile.create(data={
            "originalFilename": "mohan.jpg", "mediaType": "IMAGE", "mimeType": "image/jpeg",
            "sizeBytes": 1024, "bucket": "test", "objectKey": f"artisans/{tag}/mohan.jpg",
            "uploadedById": user.id, "artisanId": mohan.id,
        })

        products: dict[str, object] = {}
        for owner, name, price in (
            (latha, "Sambalpuri saree", 4500),
            (latha, "Cotton stole", 900),
            (mohan, "Ikat yardage", 2200),
            (outsider, "Ajrakh dupatta", 1500),
        ):
            row = await db.productdocumentation.create(data={
                "craftName": craft.name, "place": owner.place, "artisanName": owner.name,
                "productName": f"{name} {tag}", "sellingPrice": price,
                "productType": "FINISHED_GOOD",
                "rawMaterialsUsed": "Cotton yarn", "productFunctionUse": "Daily wear",
                "createdById": user.id, "artisanId": owner.id,
                "workshopId": workshop.id if owner is not outsider else None,
            })
            products[name] = row

        products["Gift box"] = await db.productdocumentation.create(data={
            "craftName": craft.name, "place": "Kutch", "artisanName": outsider.name,
            "productName": f"Gift box {tag}", "productType": "PACKAGING",
            "createdById": user.id, "artisanId": outsider.id,
        })

        await db.mediafile.create(data={
            "originalFilename": "saree.jpg", "mediaType": "IMAGE", "mimeType": "image/jpeg",
            "sizeBytes": 2048, "bucket": "test", "objectKey": f"products/{tag}/saree.jpg",
            "uploadedById": user.id, "productId": products["Sambalpuri saree"].id,
        })

        tool = await db.tooldocumentation.create(data={
            "craftName": craft.name, "place": "Barpali", "artisanName": latha.name,
            "toolkitName": f"Pit loom {tag}", "localName": "Khadi", "material": "Teak",
            "processUsedIn": "Weaving", "replacementCost": 12000,
            "createdById": user.id, "artisanId": latha.id, "workshopId": workshop.id,
        })
        process = await db.process.create(data={
            "name": f"Tie and dye {tag}", "productId": products["Sambalpuri saree"].id,
            "notes": "Yarn is tied in sections, dyed dark, then untied and washed.",
            "preProcessAvailable": True,
            "createdById": user.id, "workshopId": workshop.id,
        })
        await db.processstep.create(data={
            "processId": process.id, "name": "Tying", "sortOrder": 0,
        })
    finally:
        await db.disconnect()

    return {
        "tag": tag, "user": user, "craft": craft, "workshop": workshop,
        "latha": latha, "mohan": mohan, "outsider": outsider,
        "products": products, "tool": tool, "process": process,
    }


def _json(data):
    from prisma import Json

    return Json(data)


@pytest.fixture(scope="module")
async def client(world):
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as c:
        c.headers.update(
            {"Authorization": f"Bearer {create_access_token(subject=world['user'].id)}"}
        )
        yield c


@pytest.fixture
def linked(client, world):
    response = client.post("/api/design-workshops", json={
        "title": f"Design workshop {uuid.uuid4().hex[:6]}", "workshopId": world["workshop"].id,
    })
    assert response.status_code == 201, response.text
    return response.json()["id"]


@pytest.fixture
def unlinked(client):
    response = client.post(
        "/api/design-workshops", json={"title": f"Standalone {uuid.uuid4().hex[:6]}"}
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _refs(client, workshop_id, **params):
    response = client.get(
        f"/api/design-workshops/{workshop_id}/references", params=params
    )
    assert response.status_code == 200, response.text
    return response.json()


def _labels(payload):
    return [o["label"] for o in payload["options"]]


def _save(client, workshop_id, stage_key, entity_key, rows, **kw):
    response = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{stage_key}",
        json={
            "entries": [
                {"entityKey": entity_key, "ordinal": i, "data": row}
                for i, row in enumerate(rows)
            ],
            **kw,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def _read(client, workshop_id, stage_key, entity_key):
    payload = client.get(
        f"/api/design-workshops/{workshop_id}/stages/{stage_key}"
    ).json()
    return payload["collections"].get(entity_key, [])



async def test_the_workshop_scope_offers_the_artisans_in_the_room(client, linked, world):
    payload = _refs(client, linked, model="Artisan", scope="WORKSHOP", search=world["tag"])
    assert payload["scopedToWorkshop"] is True
    labels = _labels(payload)
    assert world["latha"].name in labels
    assert world["mohan"].name in labels
    assert world["outsider"].name not in labels


async def test_a_heavily_photographed_artisan_does_not_starve_the_next_one(client, linked, world):
    options = _refs(client, linked, model="Artisan", scope="WORKSHOP", search=world["tag"])["options"]
    photos = {o["label"]: o["data"].get("photo") for o in options}
    assert photos.get(world["latha"].name), "the over-photographed artisan lost her portrait"
    assert photos.get(world["mohan"].name), (
        "the artisan photographed most recently was starved of the shared budget"
    )


async def test_the_roster_picker_sees_the_whole_table(client, linked, world):
    labels = _labels(_refs(client, linked, model="Artisan", scope="ALL", search=world["tag"]))
    assert world["outsider"].name in labels


async def test_an_unlinked_workshop_falls_back_rather_than_showing_nothing(
    client, unlinked, world
):
    payload = _refs(client, unlinked, model="Artisan", scope="WORKSHOP", search=world["tag"])
    assert payload["scopedToWorkshop"] is False
    assert world["outsider"].name in _labels(payload)


async def test_an_unknown_scope_is_refused(client, linked):
    response = client.get(
        f"/api/design-workshops/{linked}/references",
        params={"model": "Artisan", "scope": "CLUSTER"},
    )
    assert response.status_code == 422, response.text


async def test_an_unknown_model_is_refused(client, linked):
    response = client.get(
        f"/api/design-workshops/{linked}/references", params={"model": "Sasquatch"}
    )
    assert response.status_code == 422, response.text



async def test_the_product_list_holds_only_the_chosen_artisan_s_products(
    client, linked, world
):
    payload = _refs(client, linked, model="ProductDocumentation", scope="WORKSHOP",
                    filterBy=world["latha"].id)
    assert payload["filtered"] is True
    labels = _labels(payload)
    assert len(labels) == 2
    assert all(world["latha"].name in o["sublabel"] for o in payload["options"])
    assert not any("Ikat yardage" in label for label in labels)


async def test_without_a_filter_the_workshop_s_whole_catalogue_is_offered(
    client, linked, world
):
    payload = _refs(client, linked, model="ProductDocumentation", scope="WORKSHOP")
    labels = _labels(payload)
    assert len(labels) == 3, labels
    assert not any("Ajrakh" in label for label in labels), "not at this workshop"


async def test_a_roster_entry_narrows_the_products_as_an_artisan_id_would(
    client, linked, world
):
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    entry_id = _read(client, linked, STAGE_3, "participant")[0]["_entryId"]

    payload = _refs(client, linked, model="ProductDocumentation", scope="WORKSHOP",
                    filterBy=entry_id)
    assert len(payload["options"]) == 2
    assert all(world["latha"].name in o["sublabel"] for o in payload["options"])


async def test_a_hand_typed_participant_narrows_to_nothing_rather_than_to_everything(
    client, linked
):
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "walkin", "name": "Walked in on day two"}])
    entry_id = _read(client, linked, STAGE_3, "participant")[0]["_entryId"]

    payload = _refs(client, linked, model="ProductDocumentation", filterBy=entry_id)
    assert payload["options"] == []


async def test_a_model_that_cannot_be_filtered_says_so(client, linked, world):
    response = client.get(
        f"/api/design-workshops/{linked}/references",
        params={"model": "Artisan", "filterBy": world["latha"].id},
    )
    assert response.status_code == 422, response.text



async def test_the_result_is_bounded_and_says_when_it_was_cut(client, linked, world):
    payload = _refs(client, linked, model="ProductDocumentation", scope="WORKSHOP", limit=1)
    assert len(payload["options"]) == 1
    assert payload["truncated"] is True


async def test_search_narrows_by_name(client, linked, world):
    payload = _refs(client, linked, model="Artisan", scope="WORKSHOP", search="Mohan")
    assert _labels(payload) == [world["mohan"].name]


async def test_an_in_record_reference_is_served_from_this_workshop_s_own_rows(
    client, linked, world
):
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    payload = _refs(client, linked, model="DwParticipant")
    assert payload["scopedToWorkshop"] is True
    assert _labels(payload) == [world["latha"].name]



async def test_choosing_an_artisan_fills_the_row_in(client, linked, world):
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    row = _read(client, linked, STAGE_3, "participant")[0]

    assert row["name"] == world["latha"].name
    assert row["localName"] == "ଲତା"
    assert row["specialisation"] == world["craft"].name
    assert row["experienceYears"] == 22
    assert row["village"] == "Barpali"
    assert row["gender"] == "Female"
    assert row["photo"]
    assert row["artisanRef"] == world["latha"].id, "the id stays: it is the join key"


async def test_the_designer_s_own_correction_is_not_reverted(client, linked, world):
    row_in = {"_clientKey": "p1", "artisanRef": world["latha"].id, "name": "Latha (Ammaji)"}
    _save(client, linked, STAGE_3, "participant", [row_in])
    _save(client, linked, STAGE_3, "participant", [row_in])

    row = _read(client, linked, STAGE_3, "participant")[0]
    assert row["name"] == "Latha (Ammaji)"
    assert row["village"] == "Barpali", "the blanks are still filled in"


async def test_picking_a_different_artisan_rewrites_the_row(client, linked, world):
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["mohan"].id}])

    row = _read(client, linked, STAGE_3, "participant")[0]
    assert row["name"] == world["mohan"].name
    assert row["artisanRef"] == world["mohan"].id


async def test_re_pointing_at_a_thinly_documented_artisan_clears_what_it_cannot_answer(
    client, linked, world
):
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    filled = _read(client, linked, STAGE_3, "participant")[0]
    assert filled["phone"] == "+91 9876500001", "the fixture has to start fully hydrated"
    assert filled["photo"]

    re_pointed = {k: v for k, v in filled.items() if not k.startswith("_")}
    re_pointed["_clientKey"] = "p1"
    re_pointed["artisanRef"] = world["outsider"].id
    _save(client, linked, STAGE_3, "participant", [re_pointed])

    row = _read(client, linked, STAGE_3, "participant")[0]
    assert row["artisanRef"] == world["outsider"].id
    assert row["name"] == world["outsider"].name
    assert row["village"] == "Kutch", "what the new artisan DOES say still overwrites"
    for stale in ("phone", "gender", "localName", "experienceYears", "specialisation", "photo"):
        assert not row.get(stale), f"{stale} still holds the previous artisan's answer"


async def test_a_gallery_survives_a_re_pointed_reference(client, linked, world):
    saree = world["products"]["Sambalpuri saree"]
    mine = ["photo-taken-in-the-room", "second-angle"]
    _save(client, linked, STAGE_6, "existingProduct",
          [{"_clientKey": "e1", "productRef": saree.id, "productPhotos": mine}])
    filled = _read(client, linked, STAGE_6, "existingProduct")[0]

    re_pointed = {k: v for k, v in filled.items() if not k.startswith("_")}
    re_pointed["_clientKey"] = "e1"
    re_pointed["productRef"] = world["products"]["Gift box"].id
    _save(client, linked, STAGE_6, "existingProduct", [re_pointed])

    row = _read(client, linked, STAGE_6, "existingProduct")[0]
    assert row["productRef"] == world["products"]["Gift box"].id
    assert row["productPhotos"] == mine


async def test_the_copy_survives_the_record_it_was_copied_from(client, linked, world):
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    saved = _read(client, linked, STAGE_3, "participant")[0]
    assert saved["name"] == world["latha"].name

    gone = {k: v for k, v in saved.items() if not k.startswith("_")}
    gone["_clientKey"] = "p1"
    gone["artisanRef"] = "cl00000000000000gone"
    gone["attendedDays"] = 4
    _save(client, linked, STAGE_3, "participant", [gone])

    row = _read(client, linked, STAGE_3, "participant")[0]
    assert row["name"] == world["latha"].name, "the copy is what the report prints"
    assert row["village"] == "Barpali"
    assert row["artisanRef"] == "cl00000000000000gone"
    assert row["attendedDays"] == 4, "the rest of the row still saves"


async def test_choosing_a_product_fills_the_baseline_row_in(client, linked, world):
    saree = world["products"]["Sambalpuri saree"]
    _save(client, linked, STAGE_6, "existingProduct", [{
        "_clientKey": "e1",
        "artisanRef": world["latha"].id,
        "productRef": saree.id,
    }])
    row = _read(client, linked, STAGE_6, "existingProduct")[0]

    assert row["name"] == saree.productName
    assert row["price"] == "4500.00", "money is stored two-place, never as a float"
    assert rich_text.to_plain(row["material"]) == "Cotton yarn"
    assert rich_text.to_plain(row["use"]) == "Daily wear"
    assert row["artisanName"] == world["latha"].name


async def test_a_category_that_does_not_map_is_left_for_the_designer(client, linked, world):
    saree = world["products"]["Sambalpuri saree"]
    _save(client, linked, STAGE_6, "existingProduct",
          [{"_clientKey": "e1", "productRef": saree.id}])
    assert "category" not in _read(client, linked, STAGE_6, "existingProduct")[0]


async def test_a_category_that_does_map_is_carried_across(client, linked, world):
    _save(client, linked, STAGE_6, "existingProduct",
          [{"_clientKey": "e1", "productRef": world["products"]["Gift box"].id}])
    assert _read(client, linked, STAGE_6, "existingProduct")[0]["category"] == "PACKAGING"


async def test_a_gallery_is_seeded_but_never_replaced(client, linked, world):
    saree = world["products"]["Sambalpuri saree"]
    mine = ["photo-taken-in-the-room"]
    _save(client, linked, STAGE_6, "existingProduct",
          [{"_clientKey": "e1", "productRef": saree.id, "productPhotos": mine}])
    assert _read(client, linked, STAGE_6, "existingProduct")[0]["productPhotos"] == mine

    _save(client, linked, STAGE_6, "existingProduct",
          [{"_clientKey": "e2", "productRef": saree.id}])
    seeded = next(r for r in _read(client, linked, STAGE_6, "existingProduct")
                  if r.get("_clientKey") == "e2")
    assert len(seeded["productPhotos"]) == 1


async def test_choosing_a_documented_process_fills_the_step_in(client, linked, world):
    process = world["process"]
    _save(client, linked, STAGE_5, "processStep",
          [{"_clientKey": "s1", "stepNumber": 1, "processRef": process.id}])
    row = _read(client, linked, STAGE_5, "processStep")[0]

    assert row["name"] == process.name
    assert row["description"] == process.notes
    assert row["documentedFor"] == world["products"]["Sambalpuri saree"].productName
    assert row["processRef"] == process.id, "the id stays: it is the join key"


async def test_the_documented_process_reaches_the_printed_report(client, linked, world):
    from app.services.report_builder import WorkshopData, build_report
    from app.services.report_model import ImageRef, ReportMeta, runs_text

    process = world["process"]
    _save(client, linked, STAGE_5, "processStep",
          [{"_clientKey": "s1", "stepNumber": 1, "processRef": process.id}])
    rows = _read(client, linked, STAGE_5, "processStep")

    doc, _warnings = build_report(
        WorkshopData(
            workshop_id=linked,
            title="Barpali cluster",
            collections={STAGE_5: {"processStep": rows}},
        ),
        "DETAILED_TECHNICAL",
        lambda mid: ImageRef(source=mid, width_px=800, height_px=600, mime_type="image/png"),
        meta=ReportMeta(title="Workshop", subtitle="Cluster",
                        generated_at="2026-08-08T00:00:00Z"),
    )

    printed: list[str] = []
    for block in doc.blocks:
        printed.append(runs_text(getattr(block, "runs", ()) or ()))
        for row in getattr(block, "rows", ()) or ():
            printed.extend(runs_text(cell) for cell in row)
        for _label, value in getattr(block, "pairs", ()) or ():
            printed.append(runs_text(value))
    text = "\n".join(printed)

    assert process.notes in text, "the documented process's notes never reached the page"
    assert world["products"]["Sambalpuri saree"].productName in text, \
        "the report cannot tell this cluster's sequence from another cluster's"


async def test_the_craft_picker_reaches_the_promoted_column(client, linked, world):
    response = client.put(
        f"/api/design-workshops/{linked}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup",
                           "data": {"craftRef": world["craft"].id}}]},
    )
    assert response.status_code == 200, response.text
    header = client.get(f"/api/design-workshops/{linked}").json()
    assert header["craftName"] == world["craft"].name


async def test_a_stage_one_save_without_a_title_does_not_500(client, linked, world):
    before = client.get(f"/api/design-workshops/{linked}").json()["title"]
    response = client.put(
        f"/api/design-workshops/{linked}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup",
                           "data": {"clusterName": "Barpali", "craftRef": world["craft"].id}}]},
    )
    assert response.status_code == 200, response.text
    after = client.get(f"/api/design-workshops/{linked}").json()
    assert after["title"] == before, "the workshop keeps the title it has"
    assert after["clusterName"] == "Barpali"



class _Row:
    def __init__(self, **fields):
        self.__dict__.update(fields)

    def __getattr__(self, _name):
        return None


def _matches(row, clause) -> bool:
    for key, value in clause.items():
        if key == "AND":
            if not all(_matches(row, c) for c in value):
                return False
        elif key == "OR":
            if not any(_matches(row, c) for c in value):
                return False
        elif key == "workshops":
            some = value["some"]
            links = getattr(row, "workshops", None) or []
            if not any(all(getattr(link, k, None) == v for k, v in some.items())
                       for link in links):
                return False
        elif isinstance(value, dict):
            if set(value) - {"contains", "mode"}:
                raise AssertionError(f"the fake delegate cannot evaluate {key}={value!r}")
            if str(value["contains"]).casefold() not in str(getattr(row, key, "") or "").casefold():
                return False
        elif getattr(row, key, None) != value:
            return False
    return True


class _Delegate:
    def __init__(self, rows):
        self._rows = rows

    async def find_many(self, where=None, order=None, take=None, include=None):
        hits = [r for r in self._rows if _matches(r, where or {})]
        return hits[:take] if take else hits

    async def find_unique(self, where=None):
        return next((r for r in self._rows if r.id == (where or {}).get("id")), None)


class _FakeDb:
    def __init__(self, rows_by_delegate):
        self._rows = rows_by_delegate

    def __getattr__(self, name):
        return _Delegate(self._rows.get(name, []))

    async def query_raw(self, _sql, _ids, *_binds):
        return []


_ASKING = SimpleNamespace(id="dw_1", workshopId="wsp_here")


def _product(**overrides):
    fields = {
        "id": "prd_here", "productName": "Sambalpuri saree", "workshopId": "wsp_here",
        "artisanId": "art_1", "craftName": "Ikat weaving", "artisanName": "Latha Devi",
        "createdById": "usr_1", "media": [], "location": None,
    }
    fields.update(overrides)
    return _Row(**fields)


async def _options(monkeypatch, *, rows, **kwargs):
    monkeypatch.setattr(dw, "db", _FakeDb({"productdocumentation": rows}))
    return await dw.reference_options(
        _ASKING, "ProductDocumentation", scope="WORKSHOP", viewer=SimpleNamespace(id="usr_2"),
        **kwargs,
    )


async def test_a_scanned_record_in_this_cluster_becomes_an_option(monkeypatch):
    payload = await _options(monkeypatch, rows=[_product()], record_id="prd_here")
    assert [o["id"] for o in payload["options"]] == ["prd_here"]
    assert payload["outOfScope"] is False
    assert payload["outOfScopeOption"] is None
    assert set(payload) == {"model", "scope", "scopedToWorkshop", "filtered", "truncated",
                            "outOfScope", "outOfScopeOption", "tentativeFirst", "tentativeLabel",
                            "options"}


async def test_without_the_parameter_the_answer_is_the_one_it_always_gave(monkeypatch):
    rows = [_product(), _product(id="prd_two", productName="Gamcha")]
    payload = await _options(monkeypatch, rows=rows)
    assert sorted(o["id"] for o in payload["options"]) == ["prd_here", "prd_two"]
    assert payload["outOfScope"] is False


async def test_a_scanned_record_from_another_cluster_is_reported_and_not_hidden(monkeypatch):
    payload = await _options(
        monkeypatch, rows=[_product(workshopId="wsp_elsewhere")], record_id="prd_here"
    )
    assert payload["outOfScope"] is True
    assert payload["outOfScopeOption"]["id"] == "prd_here"
    assert payload["scopedToWorkshop"] is True, "the scope was applied, not abandoned"


async def test_the_out_of_scope_row_is_not_in_the_ordinary_option_list(monkeypatch):
    payload = await _options(
        monkeypatch, rows=[_product(workshopId="wsp_elsewhere")], record_id="prd_here"
    )
    assert payload["options"] == [], "an out-of-scope row must not be renderable as a plain option"
    assert payload["outOfScopeOption"]["label"], "and it must still be carried, or nothing can offer it"


async def test_an_id_that_names_nothing_is_not_out_of_scope(monkeypatch):
    payload = await _options(monkeypatch, rows=[_product()], record_id="prd_nowhere")
    assert payload["options"] == []
    assert payload["outOfScope"] is False
    assert payload["outOfScopeOption"] is None


async def test_a_record_the_caller_may_not_read_answers_exactly_as_a_missing_one(monkeypatch):
    async def _only_mine(_viewer):
        return {"createdById": "usr_2"}

    monkeypatch.setattr(dw, "viewable_where", _only_mine)
    withheld = await _options(
        monkeypatch,
        rows=[_product(createdById="usr_1", workshopId="wsp_elsewhere")],
        record_id="prd_here",
    )
    absent = await _options(monkeypatch, rows=[], record_id="prd_here")
    assert withheld == absent, "a withheld record must be indistinguishable from a missing one"
    assert withheld["outOfScope"] is False
    assert withheld["options"] == []
    assert withheld["outOfScopeOption"] is None, "the out-of-band key must not leak it either"


async def test_the_probe_drops_the_workshop_clause_and_nothing_else(monkeypatch):
    monkeypatch.setattr(dw, "db", _FakeDb({"productdocumentation": [_product(artisanId="art_9")]}))
    payload = await dw.reference_options(
        _ASKING, "ProductDocumentation", scope="WORKSHOP", filter_by="art_1",
        record_id="prd_here", viewer=SimpleNamespace(id="usr_2"),
    )
    assert payload["filtered"] is True
    assert payload["options"] == []
    assert payload["outOfScope"] is False



def _entry(**overrides):
    fields = {"id": "ent_synced", "clientKey": "ck_made_offline", "designWorkshopId": "dw_1",
              "entityKey": "prototype", "ordinal": 0,
              "data": {"prototypeCode": "P-01", "name": "Shoulder bag"}}
    fields.update(overrides)
    return _Row(**fields)


async def _in_record(monkeypatch, *, rows, record_id=""):
    monkeypatch.setattr(dw, "db", _FakeDb({"dwstageentry": rows}))
    entity = dw._dw_entity("DwPrototype")
    assert entity is not None, "the registry no longer declares DwPrototype; this test is stale"
    return await dw._in_record_options(_ASKING, entity, None, 25, record_id=record_id)


async def test_a_tag_printed_before_the_row_synced_still_resolves(monkeypatch):
    payload = await _in_record(monkeypatch, rows=[_entry()], record_id="ck_made_offline")
    assert [o["id"] for o in payload["options"]] == ["ent_synced"], (
        "a tag carrying the row's client key must find the row, or the scan is indistinguishable "
        "from a code that names nothing"
    )


async def test_a_tag_printed_after_the_row_synced_still_resolves(monkeypatch):
    payload = await _in_record(monkeypatch, rows=[_entry()], record_id="ent_synced")
    assert [o["id"] for o in payload["options"]] == ["ent_synced"]


async def test_the_second_identifier_does_not_widen_past_this_workshop(monkeypatch):
    other = _entry(id="ent_other", clientKey="ck_elsewhere", designWorkshopId="dw_2")
    payload = await _in_record(monkeypatch, rows=[other], record_id="ck_elsewhere")
    assert payload["options"] == []
    assert payload["outOfScope"] is False, (
        "an in-record ref reports no out-of-scope row: another workshop's entry is not a "
        "candidate this field is refusing, it is not a candidate at all"
    )



def _sketch(ordinal, name, tentative=None, **overrides):
    data = {"name": name}
    if tentative is not None:
        data[dw.TENTATIVE_FIELD_KEY] = tentative
    fields = {
        "id": f"ent_{ordinal}", "clientKey": f"ck_{ordinal}", "designWorkshopId": "dw_1",
        "entityKey": "sketch", "ordinal": ordinal, "data": data,
    }
    fields.update(overrides)
    return _Row(**fields)


async def _sketches(monkeypatch, rows, *, take=25, search=None):
    monkeypatch.setattr(dw, "db", _FakeDb({"dwstageentry": rows}))
    entity = dw._dw_entity("DwSketch")
    assert entity is not None, "the registry no longer declares DwSketch; this test is stale"
    return await dw._in_record_options(_ASKING, entity, search, take)


async def test_a_tentative_sketch_is_promoted_above_the_rest(monkeypatch):
    payload = await _sketches(monkeypatch, [
        _sketch(0, "First"),
        _sketch(1, "Second", tentative=True),
        _sketch(2, "Third"),
    ])
    assert [o["id"] for o in payload["options"]] == ["ent_1", "ent_0", "ent_2"]


async def test_the_partition_is_stable_inside_each_group(monkeypatch):
    payload = await _sketches(monkeypatch, [
        _sketch(0, "A"), _sketch(1, "B", tentative=True), _sketch(2, "C"),
        _sketch(3, "D", tentative=True), _sketch(4, "E"),
    ])
    assert [o["id"] for o in payload["options"]] == ["ent_1", "ent_3", "ent_0", "ent_2", "ent_4"]


async def test_the_cap_falls_after_the_partition(monkeypatch):
    payload = await _sketches(monkeypatch, [
        _sketch(0, "A"), _sketch(1, "B"), _sketch(2, "C", tentative=True),
    ], take=2)
    assert [o["id"] for o in payload["options"]] == ["ent_2", "ent_0"]
    assert payload["truncated"] is True, "the cap still bites and must still say so"


async def test_a_tentative_row_is_never_the_one_the_cap_cuts(monkeypatch):
    rows = [_sketch(n, f"S{n}") for n in range(6)] + [_sketch(6, "Late", tentative=True)]
    payload = await _sketches(monkeypatch, rows, take=3)
    assert [o["tentative"] for o in payload["options"]] == [True, False, False]


async def test_the_flag_travels_and_the_stage_s_answers_do_not(monkeypatch):
    payload = await _sketches(monkeypatch, [
        _sketch(0, "Plain", lengthCm=40),
        _sketch(1, "Unsettled", tentative=True),
    ])
    assert [(o["id"], o["tentative"]) for o in payload["options"]] == [
        ("ent_1", True), ("ent_0", False),
    ]
    allowed = {"name", "materials", "lengthCm", "widthCm", "heightCm"}
    assert all(set(o["data"]) <= allowed for o in payload["options"]), (
        "the reference payload is deliberately narrow; a stage's answers are not a picker's business"
    )
    assert all("isTentative" not in o["data"] for o in payload["options"])


async def test_the_payload_says_it_reordered_and_names_the_registry_s_word(monkeypatch):
    payload = await _sketches(monkeypatch, [_sketch(0, "A", tentative=True)])
    assert payload["tentativeFirst"] is True
    entity = dw._dw_entity("DwSketch")
    assert payload["tentativeLabel"] == entity.field(dw.TENTATIVE_FIELD_KEY).label


async def test_a_value_a_designer_cannot_have_ticked_is_not_tentative(monkeypatch):
    payload = await _sketches(monkeypatch, [
        _sketch(0, "String", tentative="true"),
        _sketch(1, "Number", tentative=1),
        _sketch(2, "Explicit", tentative=False),
        _sketch(3, "Real", tentative=True),
    ])
    assert [o["id"] for o in payload["options"]] == ["ent_3", "ent_0", "ent_1", "ent_2"]
    assert [o["tentative"] for o in payload["options"]] == [True, False, False, False]


async def test_an_unanswered_box_leaves_the_sketch_exactly_where_it_was(monkeypatch):
    payload = await _sketches(monkeypatch, [_sketch(0, "A"), _sketch(1, "B"), _sketch(2, "C")])
    assert [o["id"] for o in payload["options"]] == ["ent_0", "ent_1", "ent_2"]
    assert all(o["tentative"] is False for o in payload["options"])


async def test_the_search_narrows_first_and_the_partition_orders_what_survived(monkeypatch):
    payload = await _sketches(monkeypatch, [
        _sketch(0, "Indigo tote"),
        _sketch(1, "Red purse", tentative=True),
        _sketch(2, "Indigo scarf", tentative=True),
    ], search="indigo")
    assert [o["id"] for o in payload["options"]] == ["ent_2", "ent_0"]


async def test_an_entity_without_the_flag_is_answered_exactly_as_before(monkeypatch):
    payload = await _in_record(monkeypatch, rows=[_entry(), _entry(id="ent_two", ordinal=1)])
    assert [o["id"] for o in payload["options"]] == ["ent_synced", "ent_two"]
    assert payload["tentativeFirst"] is False
    assert payload["tentativeLabel"] == ""
    assert all("tentative" not in o for o in payload["options"])


async def test_an_external_reference_model_makes_no_claim_about_tentativeness(monkeypatch):
    payload = await _options(monkeypatch, rows=[_product()])
    assert payload["tentativeFirst"] is False
    assert payload["tentativeLabel"] == ""
    assert all("tentative" not in o for o in payload["options"])



class _CapturingDelegate:
    def __init__(self, sink):
        self._sink = sink

    async def find_many(self, where=None, order=None, take=None, include=None):
        self._sink.append(where or {})
        return []

    async def find_unique(self, where=None):
        return None


class _CapturingDb:
    def __init__(self, sink):
        self._sink = sink

    def __getattr__(self, _name):
        return _CapturingDelegate(self._sink)

    async def query_raw(self, _sql, _ids, *_binds):
        return []


async def test_the_questionnaire_picker_carries_the_asking_account_into_the_query(monkeypatch):
    seen: list[dict] = []
    monkeypatch.setattr(dw, "db", _CapturingDb(seen))

    await dw.reference_options(
        _ASKING, "Questionnaire", scope="ALL", viewer=SimpleNamespace(id="usr_2")
    )

    assert len(seen) == 1
    clauses = seen[0]["AND"]
    account = next(c for c in clauses if "OR" in c)
    assert {"ownerId": "usr_2"} in account["OR"]
    assert {"isShared": True, "isActive": True} in account["OR"]
    assert not any("designWorkshopId" in c for c in clauses)


async def test_an_account_scoped_model_is_refused_rather_than_served_wide(monkeypatch):
    seen: list[dict] = []
    monkeypatch.setattr(dw, "db", _CapturingDb(seen))

    with pytest.raises(HTTPException) as refused:
        await dw.reference_options(_ASKING, "Questionnaire", scope="ALL")
    assert refused.value.status_code == 401
    assert seen == [], "the table was queried before the refusal"

    await dw.reference_options(_ASKING, "ToolDocumentation", scope="ALL")
    assert len(seen) == 1
