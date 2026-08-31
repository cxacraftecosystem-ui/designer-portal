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

ONE SECTION AT THE FOOT OF THIS FILE NEEDS NO DATABASE, and its own header says why: the by-id
lookup added for scanned record cards is a decision about WHICH CLAUSE IS DROPPED, not a query
whose results have to be real, and one of its assertions is the security boundary between
"withheld" and "absent". An assertion like that is worth more running everywhere than running
only where Postgres is up. It is driven through a fake Prisma client, the pattern
``test_reference_carry.py`` established for exactly this reason.
"""

import os
import uuid
from types import SimpleNamespace

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password

# The service itself, for the database-free section at the foot of this file: those tests call
# `reference_options` directly with `dw.db` swapped out, rather than going through the HTTP client.
from app.services import design_workshops as dw, rich_text

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

# THE GATE MOVED OFF ``pytestmark`` AND INTO THE ``world`` FIXTURE, and the move is the whole
# reason the section at the foot of this file can exist. A module-level skipif is applied to every
# test in the module without asking any of them whether they need a database, so the by-id tests —
# which fake the Prisma client out entirely — would have skipped on precisely the machines where
# nobody can run the database-backed ones either. Every test that needs Postgres takes ``client``,
# which takes ``world``; skipping there is the same gate expressed as a dependency, so it cannot
# accidentally cover a test that does not have one. ``_LOCAL`` is left in place because the fixture
# reads it and because ``conftest`` publishes the resolved DSN before this module is imported —
# see ``resolve_database_url`` for why that ordering is not an accident.
pytestmark = [pytest.mark.anyio]

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

    THE DATABASE GATE LIVES HERE — see the note above ``pytestmark``. Everything that needs
    Postgres reaches this fixture through ``client``; nothing that does not, does.
    """
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
    a picker the designer believes is narrowed to one artisan.

    ── WHICH MODELS THIS STILL COVERS, BECAUSE THE SET SHRANK ON 2026-08-24 ─────────────────────
    ``Process`` USED TO BE IN IT AND IS NOT ANY MORE. It now declares ``filter_field="productId"``
    and both stage-5 process pickers are narrowed by a ``productRef`` sibling, so a ``filterBy``
    against ``Process`` is honoured rather than refused. Nothing in this test asserted that, and
    nothing in it should be read as still asserting it — the parametrised subject is ``Artisan``.

    The two models that still refuse are ``Artisan`` (stage 3 is where the roster is BUILT, so
    there is nothing above it to narrow by) and ``QuestionnaireInterview`` (its link to artisans is
    a many-to-many, and the filter arm applies a scalar column name). Both are asserted, without a
    database, in ``test_process_product_cascade.py::
    test_a_model_that_still_cannot_be_filtered_still_says_so`` — deliberately there rather than
    here, because a 422 on every open of both process pickers is the failure this change could
    cause, and it must be catchable on a tree with no Postgres. That file also carries the
    executable narrowing tests: the product's processes and only those, several of them, and none
    auto-selected.
    """
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


async def test_re_pointing_at_a_thinly_documented_artisan_clears_what_it_cannot_answer(
    client, linked, world
):
    """THE REGRESSION. Half a rewrite names one artisan over another artisan's phone and face.

    The overwrite was applied field by field inside the copy loop, which skips a blank source
    value — so re-pointing a row at an artisan who has only a name and a craft rewrote the name
    and the specialisation and left the PREVIOUS artisan's phone, village, gender, local name,
    years of experience and PHOTOGRAPH standing under the new artisan's id. That row prints in the
    participant table of a .docx submitted to a ministry, and because the copy IS the historical
    record by design, nothing can ever re-resolve it.

    The row is sent back WHOLE, the way both clients really send it: the web's
    ``hydrateFromReference`` applies the same skip-the-blanks rule in the browser, so the stale
    boxes are still on screen and still in the payload when the designer saves. A test that
    re-sent only ``artisanRef`` would pass against the broken code, because there would be nothing
    stale in the request for the server to keep.

    ``outsider`` is the second artisan on purpose: they carry a name and a place and nothing else
    — no craft, no phone, no gender, no local name, no recorded experience and NO PHOTOGRAPH —
    which is an ordinary state for somebody who walked in on day two, and the only shape that
    exposes the defect. Re-pointing at ``mohan`` would not: he has a portrait and a craft of his
    own, so those two fields were overwritten even by the broken code.
    """
    _save(client, linked, STAGE_3, "participant",
          [{"_clientKey": "p1", "artisanRef": world["latha"].id}])
    filled = _read(client, linked, STAGE_3, "participant")[0]
    assert filled["phone"] == "9876500001", "the fixture has to start fully hydrated"
    assert filled["photo"]

    re_pointed = {k: v for k, v in filled.items() if not k.startswith("_")}
    re_pointed["_clientKey"] = "p1"
    re_pointed["artisanRef"] = world["outsider"].id
    _save(client, linked, STAGE_3, "participant", [re_pointed])

    row = _read(client, linked, STAGE_3, "participant")[0]
    assert row["artisanRef"] == world["outsider"].id
    assert row["name"] == world["outsider"].name
    assert row["village"] == "Kutch", "what the new artisan DOES say still overwrites"
    # And everything the new artisan cannot answer is gone rather than inherited. Absent, not
    # blank: `validate_entry` drops a key it has no value for, so the stored row simply stops
    # claiming to know the participant's telephone number — and stops carrying somebody else's
    # face into the report's participant table.
    for stale in ("phone", "gender", "localName", "experienceYears", "specialisation", "photo"):
        assert not row.get(stale), f"{stale} still holds the previous artisan's answer"


async def test_a_gallery_survives_a_re_pointed_reference(client, linked, world):
    """The multi arm is exempt from the clearing above, for the reason it is exempt from the
    overwrite: those photographs were taken in the room and there is no second copy of them.

    Asserted on the tool row rather than the product row because it is the same code path with a
    different entity, and a clearing rule that ever grew a "just pop everything mapped" shortcut
    would take the workshop's own photographs with it.
    """
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
    # `material` READS BACK AS A RICH-TEXT DOCUMENT, NOT A STRING, and this assertion said otherwise
    # until 2026-08-23. `existingProduct.material` is declared RICH in `stage_definitions.py`, so
    # hydration stores `{"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "Cotton yarn"}]}]}`.
    # Asserted through `to_plain` rather than against that literal, so the test stays about what a
    # reader SEES and does not have to be rewritten the next time the document shape gains a key.
    # BOTH OF THESE READ BACK AS RICH-TEXT DOCUMENTS, NOT STRINGS, and both assertions compared
    # against a bare string until 2026-08-23. `existingProduct.material` and `existingProduct.use`
    # are declared RICH in `stage_definitions.py` (so is `mainToolsUsed`, if a later assertion is
    # ever added for it), so hydration stores
    # `{"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "Daily wear"}]}]}`.
    # Asserted through `to_plain` rather than against that literal, so these stay about what a
    # reader SEES and do not need rewriting the next time the document shape gains a key.
    assert rich_text.to_plain(row["material"]) == "Cotton yarn"
    assert rich_text.to_plain(row["use"]) == "Daily wear"
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


# --------------------------------------------------------------------------------------
# The by-id lookup — and these need NO DATABASE
# --------------------------------------------------------------------------------------
#
# Everything above this line goes through the live stack because it is testing a `WHERE` and a
# join. What follows is testing a DECISION — which clause is dropped for the out-of-scope probe,
# and which one is never dropped — and that decision is legible with the delegate faked out. Same
# reasoning as `test_reference_carry.py`, whose module docstring spells it out: driving the REAL
# `reference_options` through a fake Prisma client is what lets the whole case sit in one readable
# literal, and it means the security assertion below still runs on a machine with no Postgres,
# which is where it is most likely to be read and least likely to be run otherwise.
#
# THE FAKE REFUSES CLAUSES IT DOES NOT UNDERSTAND, and that is the load-bearing part of it. A fake
# that ignored the workshop clause would make `test_a_scanned_record_from_another_cluster_...` pass
# while the server did nothing at all, and a fake that ignored the read predicate would make the
# security test pass while the boundary was open.


class _Row:
    """One database row. Any column not named reads as ``None``.

    The same trick `design_workshops._ProbeRow` uses and for the same reason: the data lambdas are
    total in their keys and read a couple of dozen columns each, so listing every one of them here
    would make the test about the lambda rather than about the query.
    """

    def __init__(self, **fields):
        self.__dict__.update(fields)

    def __getattr__(self, _name):
        return None


def _matches(row, clause) -> bool:
    """Does ``row`` satisfy this Prisma ``where``? Raises on any operator it has not been taught.

    The raise is the point. Silently answering True for a clause it does not understand is exactly
    how a fake certifies a filter that was never applied.
    """
    for key, value in clause.items():
        if key == "AND":
            if not all(_matches(row, c) for c in value):
                return False
        elif key == "OR":
            if not any(_matches(row, c) for c in value):
                return False
        elif key == "workshops":
            # The join-table arm of `_artisan_workshop_where`.
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
    """Enough Prisma client for :func:`reference_options`: delegates, and a photo query that
    answers nothing.

    ``order`` is accepted and not applied, and the tentative-first section at the foot of this file
    turns on row order deliberately anyway. That is faithful rather than a gap: the partition under
    test is applied to the rows AFTER they come back, never by the database, so INSERTION ORDER
    standing in for ``ordinal ASC`` is exactly the input the real query hands the real function. A
    fake that sorted would be testing Prisma. Every other test here asserts on a list of one or zero
    rows, or on ids that the insertion order already fixes.
    """

    def __init__(self, rows_by_delegate):
        self._rows = rows_by_delegate

    def __getattr__(self, name):
        return _Delegate(self._rows.get(name, []))

    async def query_raw(self, _sql, _ids, *_binds):
        return []


#: The design workshop asking. Linked to a cluster, because an UNLINKED one applies no workshop
#: clause at all and there would be nothing for the out-of-scope probe to be about.
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
    """An id can now be turned into a picker option, which is the whole of the endpoint change.

    ``search_fields`` for this model is ``productName, localName, artisanName, craftName`` and
    ``id`` is in none of them, so before ``record_id`` existed there was no query on this endpoint
    that a scanned code could be the input to.
    """
    payload = await _options(monkeypatch, rows=[_product()], record_id="prd_here")
    assert [o["id"] for o in payload["options"]] == ["prd_here"]
    assert payload["outOfScope"] is False
    assert payload["outOfScopeOption"] is None
    # THE WHOLE KEY SET, NAMED. Two clients decode this payload — `DwReferencePayload` on the web
    # and `DwReferenceResponseDto` on the handset — and a key added here without them is a promise
    # nothing keeps. `tentativeFirst`/`tentativeLabel` are the newest pair; see the section at the
    # foot of this file for what they say and why they are inert on an external model like this one.
    assert set(payload) == {"model", "scope", "scopedToWorkshop", "filtered", "truncated",
                            "outOfScope", "outOfScopeOption", "tentativeFirst", "tentativeLabel",
                            "options"}


async def test_without_the_parameter_the_answer_is_the_one_it_always_gave(monkeypatch):
    """The additive guarantee. Absent ``record_id``, the workshop's whole catalogue, as before."""
    rows = [_product(), _product(id="prd_two", productName="Gamcha")]
    payload = await _options(monkeypatch, rows=rows)
    assert sorted(o["id"] for o in payload["options"]) == ["prd_here", "prd_two"]
    assert payload["outOfScope"] is False


async def test_a_scanned_record_from_another_cluster_is_reported_and_not_hidden(monkeypatch):
    """Documented somewhere else, and no such record, used to be the same empty list.

    ``existingProduct.productRef`` is WORKSHOP-scoped, so designer A's product simply is not in
    designer B's picker — and the two demand opposite next actions from the person holding the
    printed card (link the cluster, versus scan again). The flag is what lets the form say which.
    """
    payload = await _options(
        monkeypatch, rows=[_product(workshopId="wsp_elsewhere")], record_id="prd_here"
    )
    assert payload["outOfScope"] is True
    assert payload["outOfScopeOption"]["id"] == "prd_here"
    assert payload["scopedToWorkshop"] is True, "the scope was applied, not abandoned"


async def test_the_out_of_scope_row_is_not_in_the_ordinary_option_list(monkeypatch):
    """THE DEFAULT IS SILENCE, and this is the assertion that keeps it that way.

    Every picker in the tree renders `payload.options` and nothing else, and
    ``StageReferenceField``'s "Nothing is documented under this design workshop's linked workshop
    yet" notice is gated on that list being EMPTY. So a cross-cluster row placed in ``options``
    would both appear as an ordinary choice and silence the only sentence that would have
    questioned it: the designer taps it and the stage row points at another cluster's record with
    nothing on screen having said so. The row belongs beside the list, never in it.
    """
    payload = await _options(
        monkeypatch, rows=[_product(workshopId="wsp_elsewhere")], record_id="prd_here"
    )
    assert payload["options"] == [], "an out-of-scope row must not be renderable as a plain option"
    assert payload["outOfScopeOption"]["label"], "and it must still be carried, or nothing can offer it"


async def test_an_id_that_names_nothing_is_not_out_of_scope(monkeypatch):
    """The flag means "found, and excluded". It must not come to mean "not found"."""
    payload = await _options(monkeypatch, rows=[_product()], record_id="prd_nowhere")
    assert payload["options"] == []
    assert payload["outOfScope"] is False
    assert payload["outOfScopeOption"] is None


async def test_a_record_the_caller_may_not_read_answers_exactly_as_a_missing_one(monkeypatch):
    """THE SECURITY BOUNDARY, and it is the reason the probe is a filtered query.

    ``records.require_record`` raises 404 and never 403 so that a refusal cannot confirm that an
    identifier names something — ``frontend/lib/workshopCodeLookup.ts`` explains what the other
    behaviour is worth to somebody holding a stack of printed cards. The by-id probe here is the
    same question asked as a ``where``: it keeps the ``viewable_where`` predicate composed at the
    top of ``reference_options`` and drops only the workshop clause, so a row that predicate
    excludes produces no rows and ``outOfScope`` stays False.

    ``viewable_where`` returns ``{}`` today — every signed-in account may read every row — so the
    predicate is faked here to a narrowing one. That is the point of the test rather than a
    weakness of it: it pins the SHAPE of the answer for the day the read policy stops being empty,
    which is the day the by-id path would otherwise start telling "withheld" from "absent".
    """
    async def _only_mine(_viewer):
        return {"createdById": "usr_2"}

    monkeypatch.setattr(dw, "viewable_where", _only_mine)
    # Somebody else's product, sitting in another cluster: both reasons to withhold it at once.
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
    """The cascade survives the by-id path, which is what stops it mis-attributing a product.

    A product of ANOTHER artisan, scanned while the row's artisan picker is set, must not come
    back as "out of this workshop" — it IS in the workshop, and offering it under this artisan's
    name is the defect ``filter_by``'s own refusal exists to prevent. So the probe keeps the
    artisan clause and the answer is silence.
    """
    monkeypatch.setattr(dw, "db", _FakeDb({"productdocumentation": [_product(artisanId="art_9")]}))
    payload = await dw.reference_options(
        _ASKING, "ProductDocumentation", scope="WORKSHOP", filter_by="art_1",
        record_id="prd_here", viewer=SimpleNamespace(id="usr_2"),
    )
    assert payload["filtered"] is True
    assert payload["options"] == []
    assert payload["outOfScope"] is False


# ── THE IN-RECORD PICKER'S BY-ID PATH, AND THE HALF OF THE CODE GRAMMAR IT USED TO MISS ────────
#
# A `Dw…` ref points at a row of THIS workshop, and those rows answer to two identifiers, not one.
# `workshopCodeIdForRow` in `frontend/lib/workshopCodes.ts` prints `_entryId` when the row has
# reached the server and `_clientKey` when it has not — a prototype tag has to be printable the
# afternoon the prototype is made, and a workshop can go a fortnight without signal — and
# `workshopCodeMatchesRow` beside it matches on either. Narrowing on `id` alone therefore answered
# every tag printed before a sync with the empty list that is byte-identical to "no such record",
# which is the exact ambiguity `record_id` exists to remove.


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
    """The client key is what a tag printed in a village without signal actually carries."""
    payload = await _in_record(monkeypatch, rows=[_entry()], record_id="ck_made_offline")
    assert [o["id"] for o in payload["options"]] == ["ent_synced"], (
        "a tag carrying the row's client key must find the row, or the scan is indistinguishable "
        "from a code that names nothing"
    )


async def test_a_tag_printed_after_the_row_synced_still_resolves(monkeypatch):
    """The other spelling, unchanged: the server id is what a tag printed on a connection holds."""
    payload = await _in_record(monkeypatch, rows=[_entry()], record_id="ent_synced")
    assert [o["id"] for o in payload["options"]] == ["ent_synced"]


async def test_the_second_identifier_does_not_widen_past_this_workshop(monkeypatch):
    """Two spellings of ONE row, not a second way in. The workshop clause still governs.

    `_in_record_options` is scoped to this design workshop whatever the field's declared scope
    says, and matching a second column must not become an exception to that: another workshop's
    prototype is not a candidate here even when its client key is the code that was scanned.
    """
    other = _entry(id="ent_other", clientKey="ck_elsewhere", designWorkshopId="dw_2")
    payload = await _in_record(monkeypatch, rows=[other], record_id="ck_elsewhere")
    assert payload["options"] == []
    assert payload["outOfScope"] is False, (
        "an in-record ref reports no out-of-scope row: another workshop's entry is not a "
        "candidate this field is refusing, it is not a candidate at all"
    )


# ── TENTATIVE-FIRST WHERE A SKETCH IS *CHOSEN*, NOT ONLY WHERE ONE IS LISTED ───────────────────
#
# The owner, 2026-08-30: a designer marks a sketch tentative "to bring them to the top of the
# list". Stage 11's sketches already sorted tentative-first wherever they were LISTED — the upload
# chooser on both clients, through `tentativeFirst` / `dwTentativeFirst` — and did NOT where one is
# CHOSEN. The three `ref_model="DwSketch"` pickers (`sketch.supersedesSketch`,
# `sketchReview.sketchRef`, `prototype.sketchRef`) are all answered by `_in_record_options`, and
# the flag was not on the wire at all, so no client could have drawn or applied it.
#
# THE ORDERING IS ON THE SERVER AND THESE TESTS ARE MOSTLY ABOUT WHY. This list is CAPPED, so a
# browser sorting what arrived would sort one page and leave a tentative sketch stranded behind the
# cap — "a client-side filter over a server-truncated list", the trap §11.5 of the frontend
# contract names. `test_the_cap_falls_after_the_partition` is the assertion that would fail if the
# partition were ever moved below the slice, or the slice pushed into the query as a `take`.
#
# No database: the fake Prisma client above is enough, for the reason the by-id section gives.


def _sketch(ordinal, name, tentative=None, **overrides):
    """One stage-11 sketch row, in `ordinal` order because that is the order the query asks for.

    `_Delegate.find_many` accepts `order` and does not apply it, so INSERTION ORDER stands in for
    `ordinal ASC` here — which is faithful, since the ordering under test is applied to the rows
    after they come back and never by the database.
    """
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
    """The whole feature, at the surface it was missing from."""
    payload = await _sketches(monkeypatch, [
        _sketch(0, "First"),
        _sketch(1, "Second", tentative=True),
        _sketch(2, "Third"),
    ])
    assert [o["id"] for o in payload["options"]] == ["ent_1", "ent_0", "ent_2"]


async def test_the_partition_is_stable_inside_each_group(monkeypatch):
    """A PARTITION, not a sort key — the designer's own arrangement survives within each group.

    This is what makes unticking the box lossless: nothing was ever written to `ordinal`, so a row
    returns to exactly the place it would have had.
    """
    payload = await _sketches(monkeypatch, [
        _sketch(0, "A"), _sketch(1, "B", tentative=True), _sketch(2, "C"),
        _sketch(3, "D", tentative=True), _sketch(4, "E"),
    ])
    assert [o["id"] for o in payload["options"]] == ["ent_1", "ent_3", "ent_0", "ent_2", "ent_4"]


async def test_the_cap_falls_after_the_partition(monkeypatch):
    """THE ASSERTION THIS LANE EXISTS FOR. Truncating first would have fixed nothing.

    The tentative sketch is the LAST row by `ordinal`. Cap the list at two before ordering and it
    is cut, and a client-side sort over what arrived can never bring it back — which is precisely
    the shape of the defect the stage lane reported: an ordering applied to one page of a
    server-truncated list.
    """
    payload = await _sketches(monkeypatch, [
        _sketch(0, "A"), _sketch(1, "B"), _sketch(2, "C", tentative=True),
    ], take=2)
    assert [o["id"] for o in payload["options"]] == ["ent_2", "ent_0"]
    assert payload["truncated"] is True, "the cap still bites and must still say so"


async def test_a_tentative_row_is_never_the_one_the_cap_cuts(monkeypatch):
    """The property the picker's own truncation sentence is allowed to state.

    Because the promotion happens above the slice, a tentative row can only be cut once EVERY
    tentative row ahead of it has been drawn. So a picker showing no tentative row is a picker
    whose whole matched set holds none — which is what lets the web say "the cap falls on the rest
    first" instead of hedging.
    """
    rows = [_sketch(n, f"S{n}") for n in range(6)] + [_sketch(6, "Late", tentative=True)]
    payload = await _sketches(monkeypatch, rows, take=3)
    assert [o["tentative"] for o in payload["options"]] == [True, False, False]


async def test_the_flag_travels_and_the_stage_s_answers_do_not(monkeypatch):
    """One boolean on the option, and `data` still empty.

    `data` is the HYDRATION dictionary — `DW_REFERENCE_HYDRATION` maps its keys onto the fields of
    the entity being filled in — and `sketch.supersedesSketch` fills a `sketch`, which declares
    `isTentative` itself. Putting the flag in `data` would therefore be a standing offer to tick
    the new sketch's box from the old one's.
    """
    payload = await _sketches(monkeypatch, [
        _sketch(0, "Plain", lengthCm=40),
        _sketch(1, "Unsettled", tentative=True),
    ])
    assert [(o["id"], o["tentative"]) for o in payload["options"]] == [
        ("ent_1", True), ("ent_0", False),
    ]
    assert all(o["data"] == {} for o in payload["options"]), (
        "the reference payload is deliberately narrow; a stage's answers are not a picker's business"
    )


async def test_the_payload_says_it_reordered_and_names_the_registry_s_word(monkeypatch):
    """A reordered list with no visible reason is a list that looks arbitrary.

    The word is the REGISTRY's, sent from the server, because a picker holds the REF field's
    `refModel` and not the referenced entity's schema — the alternative is two clients each
    resolving a second entity to learn one string, and two more places for it to go stale.
    """
    payload = await _sketches(monkeypatch, [_sketch(0, "A", tentative=True)])
    assert payload["tentativeFirst"] is True
    entity = dw._dw_entity("DwSketch")
    assert payload["tentativeLabel"] == entity.field(dw.TENTATIVE_FIELD_KEY).label


async def test_a_value_a_designer_cannot_have_ticked_is_not_tentative(monkeypatch):
    """`is True` and nothing looser, matching `isTentativeRow` and the BOOL control exactly.

    `coerce_value` stores a real boolean for a BOOL field, so these three are values nothing in the
    repository writes. Promoting one would put a row at the top of this picker whose own checkbox
    on the stage form reads "Not answered" — one record disagreeing with itself about one field.
    """
    payload = await _sketches(monkeypatch, [
        _sketch(0, "String", tentative="true"),
        _sketch(1, "Number", tentative=1),
        _sketch(2, "Explicit", tentative=False),
        _sketch(3, "Real", tentative=True),
    ])
    assert [o["id"] for o in payload["options"]] == ["ent_3", "ent_0", "ent_1", "ent_2"]
    assert [o["tentative"] for o in payload["options"]] == [True, False, False, False]


async def test_an_unanswered_box_leaves_the_sketch_exactly_where_it_was(monkeypatch):
    """The owner's own second clause: an unticked sketch is treated as it always was."""
    payload = await _sketches(monkeypatch, [_sketch(0, "A"), _sketch(1, "B"), _sketch(2, "C")])
    assert [o["id"] for o in payload["options"]] == ["ent_0", "ent_1", "ent_2"]
    assert all(o["tentative"] is False for o in payload["options"])


async def test_the_search_narrows_first_and_the_partition_orders_what_survived(monkeypatch):
    """Two narrowings in the right order. The search decides membership; the flag decides rank."""
    payload = await _sketches(monkeypatch, [
        _sketch(0, "Indigo tote"),
        _sketch(1, "Red purse", tentative=True),
        _sketch(2, "Indigo scarf", tentative=True),
    ], search="indigo")
    assert [o["id"] for o in payload["options"]] == ["ent_2", "ent_0"]


async def test_an_entity_without_the_flag_is_answered_exactly_as_before(monkeypatch):
    """`prototype` declares no such field, and that is an ordinary state rather than an error.

    No key on the options, no claim in the payload — so neither client can draw the word for a
    picker the server did not partition.
    """
    payload = await _in_record(monkeypatch, rows=[_entry(), _entry(id="ent_two", ordinal=1)])
    assert [o["id"] for o in payload["options"]] == ["ent_synced", "ent_two"]
    assert payload["tentativeFirst"] is False
    assert payload["tentativeLabel"] == ""
    assert all("tentative" not in o for o in payload["options"])


async def test_an_external_reference_model_makes_no_claim_about_tentativeness(monkeypatch):
    """An Artisan or a ProductDocumentation has no stage-entry `data` and therefore no such flag."""
    payload = await _options(monkeypatch, rows=[_product()])
    assert payload["tentativeFirst"] is False
    assert payload["tentativeLabel"] == ""
    assert all("tentative" not in o for o in payload["options"])
