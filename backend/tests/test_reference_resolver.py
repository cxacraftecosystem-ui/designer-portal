"""The reference pickers and the hydration that follows them, against a real database.

The requirement these serve is one sentence: a designer should populate a stage from the
records the system already holds instead of retyping them, and picking the artisan should
narrow the product list to that artisan's products. Neither half can be tested without
Postgres — the scoping is a `WHERE`, the cascade is a join through a stage entry, and the
hydration is a read of four tables — so the module skips itself when ``DATABASE_URL`` does not
point at a local database.

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

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

STAGE_3 = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
STAGE_5 = "TRADITIONAL_PROCESS_BASELINE"
STAGE_6 = "EXISTING_PRODUCTS_BASELINE"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """One cluster's worth of records: a workshop, three artisans, four products, one tool.

    Two of the artisans reach the workshop by DIFFERENT routes — one through the explicit
    column, one through the WorkshopArtisan join — because the picker has to find both. An
    artisan that reaches it by neither is what proves the scope is doing anything at all.
    """
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

        # Reaches the workshop through the explicit column.
        latha = await db.artisan.create(data={
            "name": f"Latha Devi {tag}", "localName": "ଲତା", "gender": "Female",
            "phone": "9876500001", "place": "Barpali", "createdById": user.id,
            "craftId": craft.id, "locationId": location.id, "workshopId": workshop.id,
            "extraMetadata": _json({"experienceYears": 22}),
        })
        # Reaches it only through the join table.
        mohan = await db.artisan.create(data={
            "name": f"Mohan Meher {tag}", "place": "Sonepur", "createdById": user.id,
            "craftId": craft.id,
        })
        await db.workshopartisan.create(
            data={"workshopId": workshop.id, "artisanId": mohan.id}
        )
        # At no workshop at all.
        outsider = await db.artisan.create(data={
            "name": f"Sita Bai {tag}", "place": "Kutch", "createdById": user.id,
        })

        await db.mediafile.create(data={
            "originalFilename": "latha.jpg", "mediaType": "IMAGE", "mimeType": "image/jpeg",
            "sizeBytes": 1024, "bucket": "test", "objectKey": f"artisans/{tag}/latha.jpg",
            "uploadedById": user.id, "artisanId": latha.id,
        })
        # LATHA IS DELIBERATELY OVER-PHOTOGRAPHED, and mohan's single portrait is deliberately the
        # NEWEST row of the pair. `_reference_photos` used to read `take=len(ids) * 4` rows ordered
        # by createdAt across ALL the ids at once, so with two artisans in scope the eight oldest
        # rows — every one of them latha's — consumed the whole budget and mohan hydrated with no
        # photo at all. See `test_a_heavily_photographed_artisan_does_not_starve_the_next_one`.
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
        # FINISHED_GOOD is deliberate: it is a ProductType with no honest counterpart in the
        # workshop registry's PRODUCT_CATEGORY, which is what the category test turns on.
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
                # The outsider's product is not at this workshop; the other three are.
                "workshopId": workshop.id if owner is not outsider else None,
            })
            products[name] = row

        # The one ProductType that does map onto a workshop category, so the mapping is proved
        # to work rather than merely proved to refuse. Not at the workshop, so it changes no
        # count in the scope tests.
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
        # NOTES AND A PARENT PRODUCT, because those are the two columns the process picker now
        # copies onto a step. A `Process` with neither would let the widened mapping pass while
        # copying nothing, which is the shape of the defect it was written to end.
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
    """A design workshop LINKED to the Workshop record, which is what a WORKSHOP scope reads."""
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


# --------------------------------------------------------------------------------------
# Scope
# --------------------------------------------------------------------------------------


async def test_the_workshop_scope_offers_the_artisans_in_the_room(client, linked, world):
    """Both readings of "at this workshop", and nobody else.

    An artisan reaches a workshop either through the explicit column on their record or through
    the WorkshopArtisan join. Reading one of them would leave the designer staring at a picker
    that does not contain the person sitting in front of them, and they would type the name in
    — which is the behaviour this whole feature exists to end.
    """
    payload = _refs(client, linked, model="Artisan", scope="WORKSHOP", search=world["tag"])
    assert payload["scopedToWorkshop"] is True
    labels = _labels(payload)
    assert world["latha"].name in labels
    assert world["mohan"].name in labels
    assert world["outsider"].name not in labels


async def test_a_heavily_photographed_artisan_does_not_starve_the_next_one(client, linked, world):
    """ONE PHOTOGRAPH PER PARENT, not one budget shared between the parents.

    `_reference_photos` used to ask for `take=len(ids) * 4` rows ordered by `createdAt asc` across
    every id at once. Two artisans in scope meant eight rows for both of them, and latha's thirteen
    older pictures took all eight — so mohan, whose only portrait is the NEWEST row in the pair,
    came back with `photo` empty. In a roster of forty that is a report printed for a visiting
    officer with faces missing for people whose portraits sit one join away in the media table, and
    re-saving the stage never fixed it because the same rows won the budget every time. It rendered
    cleanly, which is why it survived.
    """
    options = _refs(client, linked, model="Artisan", scope="WORKSHOP", search=world["tag"])["options"]
    photos = {o["label"]: o["data"].get("photo") for o in options}
    assert photos.get(world["latha"].name), "the over-photographed artisan lost her portrait"
    assert photos.get(world["mohan"].name), (
        "the artisan photographed most recently was starved of the shared budget"
    )


async def test_the_roster_picker_sees_the_whole_table(client, linked, world):
    """Stage 3's own scope. The artisan who walks in on day two is not linked to anything yet,
    and a picker that cannot offer them is a picker the designer types around."""
    labels = _labels(_refs(client, linked, model="Artisan", scope="ALL", search=world["tag"]))
    assert world["outsider"].name in labels


async def test_an_unlinked_workshop_falls_back_rather_than_showing_nothing(
    client, unlinked, world
):
    """The Workshop link is optional and is often made days after capture starts. A
    WORKSHOP-scoped picker on an unlinked record would otherwise be permanently empty with
    nothing on screen to explain why; the response says which of the two happened so the form
    can label the list honestly."""
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


# --------------------------------------------------------------------------------------
# The cascade
# --------------------------------------------------------------------------------------


async def test_the_product_list_holds_only_the_chosen_artisan_s_products(
    client, linked, world
):
    """THE SENTENCE THE REQUIREMENT IS MADE OF: "in the products dropdown, only the products
    from the selected artisan should appear"."""
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
    """Stage 13 holds a DwParticipant id in the field stage 6 holds an Artisan id in, and the
    same cascade hangs off both. Making the client know the difference would put a rule about
    the registry's internals into three codebases."""
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
    """An artisan typed in by hand has no record behind them, so there are no documented
    products to attribute to them. Falling back to the whole catalogue would invite the designer
    to attach somebody else's work to this row."""
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "walkin", "name": "Walked in on day two"}])
    entry_id = _read(client, linked, STAGE_3, "participant")[0]["_entryId"]

    payload = _refs(client, linked, model="ProductDocumentation", filterBy=entry_id)
    assert payload["options"] == []


async def test_a_model_that_cannot_be_filtered_says_so(client, linked, world):
    """Reported rather than ignored: silently dropping the filter would serve the whole table to
    a picker the designer believes is narrowed to one artisan."""
    response = client.get(
        f"/api/design-workshops/{linked}/references",
        params={"model": "Artisan", "filterBy": world["latha"].id},
    )
    assert response.status_code == 422, response.text


# --------------------------------------------------------------------------------------
# Bounding and search
# --------------------------------------------------------------------------------------


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
    """A DwSketch reference is not a candidate list drawn from other workshops: offering them
    would produce a report whose prototype table cites drawings that appear nowhere in it."""
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    payload = _refs(client, linked, model="DwParticipant")
    assert payload["scopedToWorkshop"] is True
    assert _labels(payload) == [world["latha"].name]


# --------------------------------------------------------------------------------------
# Hydration
# --------------------------------------------------------------------------------------


async def test_choosing_an_artisan_fills_the_row_in(client, linked, world):
    """The designer sends one id and gets a filled row back — which is the whole point."""
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
    """A name the artisan prefers, typed in the room. A picker that silently changed it back on
    every save would be worse than retyping, because the designer would watch the value revert
    with no way to make it stick."""
    row_in = {"_clientKey": "p1", "artisanRef": world["latha"].id, "name": "Latha (Ammaji)"}
    _save(client, linked, STAGE_3, "participant", [row_in])
    _save(client, linked, STAGE_3, "participant", [row_in])

    row = _read(client, linked, STAGE_3, "participant")[0]
    assert row["name"] == "Latha (Ammaji)"
    assert row["village"] == "Barpali", "the blanks are still filled in"


async def test_picking_a_different_artisan_rewrites_the_row(client, linked, world):
    """The one case that overwrites. Leaving the old artisan's name beside the new artisan's id
    would have the report and the research data naming two different people for one row."""
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["mohan"].id}])

    row = _read(client, linked, STAGE_3, "participant")[0]
    assert row["name"] == world["mohan"].name
    assert row["artisanRef"] == world["mohan"].id


async def test_the_copy_survives_the_record_it_was_copied_from(client, linked, world):
    """THE REASON THE COPY EXISTS. A workshop report is a historical document; the artisan
    record behind it is live data that gets merged into a duplicate, corrected or deleted.
    Resolving the name through the id at render time would blank a participant table years
    after it was submitted, which is worse than useless: the table is the proof of who attended.

    The gone record is stood in for by an id that resolves to nothing, which is exactly the
    state a deleted row leaves behind and reaches the same branch. Note that the row is sent
    back the way a real client sends it — whole, copied fields and all, because a stage save
    REPLACES the row it names — so what is being asserted is that hydration does not blank a
    copy whose reference has vanished, which is the only chance it gets to.
    """
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
    assert row["material"] == "Cotton yarn"
    assert row["use"] == "Daily wear"
    assert row["artisanName"] == world["latha"].name


async def test_a_category_that_does_not_map_is_left_for_the_designer(client, linked, world):
    """ProductType and PRODUCT_CATEGORY answer different questions. Guessing across them would
    fill a ministry report's category column with plausible, wrong values nobody would check."""
    saree = world["products"]["Sambalpuri saree"]
    _save(client, linked, STAGE_6, "existingProduct",
          [{"_clientKey": "e1", "productRef": saree.id}])
    assert "category" not in _read(client, linked, STAGE_6, "existingProduct")[0]


async def test_a_category_that_does_map_is_carried_across(client, linked, world):
    _save(client, linked, STAGE_6, "existingProduct",
          [{"_clientKey": "e1", "productRef": world["products"]["Gift box"].id}])
    assert _read(client, linked, STAGE_6, "existingProduct")[0]["category"] == "PACKAGING"


async def test_a_gallery_is_seeded_but_never_replaced(client, linked, world):
    """Replacing the photographs a designer took at the workshop with the catalogue shot would
    destroy the only copy of them that exists."""
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
    """THE GAP THIS LANE EXISTS TO CLOSE.

    A process step used to hydrate one field. The stage it sits in is one of the report's
    substantive narrative sections, and its rows printed "Tie and dye" and nothing else while the
    `Process` record they pointed at held the notes describing what actually happens and the
    product the sequence belongs to.
    """
    process = world["process"]
    _save(client, linked, STAGE_5, "processStep",
          [{"_clientKey": "s1", "stepNumber": 1, "processRef": process.id}])
    row = _read(client, linked, STAGE_5, "processStep")[0]

    assert row["name"] == process.name
    assert row["description"] == process.notes
    assert row["documentedFor"] == world["products"]["Sambalpuri saree"].productName
    assert row["processRef"] == process.id, "the id stays: it is the join key"


async def test_the_documented_process_reaches_the_printed_report(client, linked, world):
    """HYDRATED IS NOT THE SAME AS PRINTED, and a field that is one but not the other fails
    silently in a document nobody re-reads.

    So this walks the whole path the ministry copy takes — pick a record, save, read the stage
    back, render — and looks for the copied words in the blocks the writers turn into a .docx and
    a .pdf. `description` lands in the process table; `documentedFor` is a KEY_VALUE and lands in
    the overflow beneath it, which is a different renderer branch and had to be checked as well.
    """
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
    """Stage 1's craft ref fills in craftName, and craftName is a PROMOTED column — so the
    hydration has to happen before the promotion is read, or the workshop list shows no craft
    for a record whose stage 1 plainly names one."""
    response = client.put(
        f"/api/design-workshops/{linked}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup",
                           "data": {"craftRef": world["craft"].id}}]},
    )
    assert response.status_code == 200, response.text
    header = client.get(f"/api/design-workshops/{linked}").json()
    assert header["craftName"] == world["craft"].name


async def test_a_stage_one_save_without_a_title_does_not_500(client, linked, world):
    """``title`` is the only promoted column DesignWorkshop declares NOT NULL, so blanking it is
    not an empty cell — it is a MissingRequiredValueError that fails the whole stage save. The
    path is ordinary: fill in the craft and the cluster, save before typing the stage's own
    title field, lose two dozen answers to a 500 that names none of them."""
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
