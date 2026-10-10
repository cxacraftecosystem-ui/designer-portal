"""Three reads built from the gap sweep (F5, F13, F8), and who each one admits and refuses.

* **F5 — a workshop's own files for the people who read it read-only.**
  ``GET /design-workshop-inspections/{id}/media`` (its inspectors) and
  ``GET /design-workshop-oversight/assigned/{id}/media`` (whoever holds its Assistant or Regional
  Director post — a Ministry Admin included). Each role is asserted both ways: admitted on the
  workshop its row names, refused on a workshop it holds no row on, and refused on the other surface.
  The URL that travels is a SIGNATURE, never the stored one, and the key and the public URL do not
  travel at all; a file of another workshop is not in the answer.
* **F5 — the questions behind the custom answers** travel on both reads as ``customSections``.
* **F13 — the inspection list's filters** narrow the inspector's own rows and can never add one.
* **F8 — ``GET /design-ratings/workshops``**, the directory of workshops that opened a piece to the
  pool: listed only when a piece is open, five facts per row, and refused (404) to everybody the POOL
  round refuses.

THE SHAPE IS THE ONE ``tests/conftest.py`` PRESCRIBES: a SYNC module fixture seeds everything inside
one ``asyncio.run`` and only then starts the ``TestClient``; the tests are sync and never touch
``db``. The storage signer is replaced by a recorder, so the assertions do not depend on whether this
machine holds storage credentials, and so "signed" is a fact the test can see.
"""

import asyncio
import os
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.design_ratings import POOL_OPENS_WHEN_FIELD
from prisma import Json

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
]

ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("admin", "ADMIN"),
    ("creator", "ADMIN"),
    ("designer", "DESIGNER"),
    ("inspector", "INSPECTOR"),
    ("other_inspector", "INSPECTOR"),
    ("idle_inspector", "INSPECTOR"),
    ("director", "ASSISTANT_DIRECTOR"),
    ("other_director", "REGIONAL_DIRECTOR"),
    ("ministry", "MINISTRY_ADMIN"),
    ("idle_ministry", "MINISTRY_ADMIN"),
    ("professor", "PROFESSOR"),
)

SIGNED = "https://signed.example.invalid/"


def _media(stamp: str, name: str, workshop_id: str | None, uploader: str, kind: str) -> dict:
    return {
        "originalFilename": f"{name}.bin",
        "mediaType": kind,
        "mimeType": {"IMAGE": "image/jpeg", "AUDIO": "audio/mpeg", "PDF": "application/pdf"}[kind],
        "sizeBytes": 1024,
        "bucket": "test-bucket",
        "objectKey": f"media/{uploader}/{stamp}-{name}",
        "url": f"https://public.example.invalid/media/{stamp}-{name}",
        "uploadedById": uploader,
        **(
            {"linkedRecordType": "designWorkshop", "linkedRecordId": workshop_id}
            if workshop_id
            else {}
        ),
    }


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            people: dict[str, Any] = {}
            for slug, role in ACCOUNTS:
                people[slug] = await db.user.create(data={
                    "email": f"reader-{slug}-{stamp}@example.org",
                    "name": f"Reader {slug} {stamp}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                })

            def workshop(title: str, **extra: Any):
                return db.designworkshop.create(data={
                    "title": f"{title} {stamp}",
                    "createdById": people["creator"].id,
                    **extra,
                })

            inspected = await workshop(
                "Ikat under inspection",
                status="PRE_SUBMISSION",
                submissionRound=2,
                state="Odisha",
                workshopKind="DESIGN_PROTOTYPE_DEVELOPMENT",
                startDate=datetime(2026, 3, 10, 4, 0, tzinfo=UTC),
            )
            elsewhere = await workshop(
                "Bandhani elsewhere",
                status="NEEDS_REVISION",
                submissionRound=1,
                state="Gujarat",
                startDate=datetime(2026, 6, 1, 4, 0, tzinfo=UTC),
            )
            second_of_mine = await workshop(
                "Kantha also inspected",
                status="IN_PROGRESS",
                state="West Bengal",
                startDate=datetime(2026, 8, 20, 4, 0, tzinfo=UTC),
            )
            unpublished = await workshop("Dhokra with nothing opened")
            gone = await workshop("Deleted but opened", deletedAt=datetime(2026, 9, 1, tzinfo=UTC))

            for row in (
                (inspected.id, "inspector"),
                (second_of_mine.id, "inspector"),
                (elsewhere.id, "other_inspector"),
            ):
                await db.designworkshopinspector.create(data={
                    "designWorkshopId": row[0],
                    "userId": people[row[1]].id,
                    "assignedById": people["admin"].id,
                })
            for workshop_id, capacity, slug in (
                (inspected.id, "ASSISTANT_DIRECTOR", "director"),
                (inspected.id, "REGIONAL_DIRECTOR", "ministry"),
                (elsewhere.id, "REGIONAL_DIRECTOR", "other_director"),
            ):
                await db.designworkshopoversight.create(data={
                    "designWorkshopId": workshop_id,
                    "capacity": capacity,
                    "userId": people[slug].id,
                    "assignedById": people["admin"].id,
                })

            uploader = people["designer"].id
            photo = await db.mediafile.create(
                data=_media(stamp, "loom", inspected.id, uploader, "IMAGE")
            )
            recording = await db.mediafile.create(
                data=_media(stamp, "interview", inspected.id, uploader, "AUDIO")
            )
            foreign = await db.mediafile.create(
                data=_media(stamp, "foreign", elsewhere.id, uploader, "PDF")
            )
            untagged = await db.mediafile.create(
                data=_media(stamp, "loose", None, uploader, "IMAGE")
            )

            section = await db.dwcustomsection.create(data={
                "designWorkshopId": inspected.id,
                "stageKey": "WORKSHOP_SETUP",
                "key": f"extra_{stamp}",
                "title": "Local questions",
                "createdById": people["creator"].id,
            })
            await db.dwcustomfield.create(data={
                "sectionId": section.id,
                "key": f"q_{stamp}",
                "label": "Which mandi buys the cloth?",
                "type": "TEXT",
            })

            def piece(workshop_id: str, entity: str, ordinal: int, opened: bool):
                data: dict[str, Any] = {"name": f"{entity} {ordinal}", "targetMarket": "Gifting"}
                if opened:
                    data[POOL_OPENS_WHEN_FIELD] = "2026-08-10"
                return db.dwstageentry.create(data={
                    "designWorkshopId": workshop_id,
                    "stageKey": "PROTOTYPE_DEVELOPMENT" if entity == "prototype" else "SKETCH_DEVELOPMENT",
                    "entityKey": entity,
                    "ordinal": ordinal,
                    "data": Json(data),
                    "createdById": people["designer"].id,
                })

            await piece(inspected.id, "prototype", 0, True)
            await piece(inspected.id, "prototype", 1, True)
            await piece(inspected.id, "sketch", 0, True)
            await piece(inspected.id, "sketch", 1, False)
            await piece(unpublished.id, "prototype", 0, False)
            await piece(gone.id, "prototype", 0, True)

            return {
                "people": people,
                "stamp": stamp,
                "inspected": inspected.id,
                "elsewhere": elsewhere.id,
                "second": second_of_mine.id,
                "unpublished": unpublished.id,
                "gone": gone.id,
                "photo": photo.id,
                "recording": recording.id,
                "foreign": foreign.id,
                "untagged": untagged.id,
            }
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())

    import app.services.s3 as s3

    signed: list[str] = []

    def fake_presign(object_key: str, **_kwargs: Any) -> str:
        signed.append(object_key)
        return f"{SIGNED}{object_key}?sig=1"

    original = s3.presign_get_url
    s3.presign_get_url = fake_presign
    try:
        with TestClient(app) as client:
            seeded["client"] = client
            seeded["signed"] = signed
            yield seeded
    finally:
        s3.presign_get_url = original


def _as(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _get(world: dict[str, Any], slug: str, path: str):
    return world["client"].get(f"/api{path}", headers=_as(world, slug))


# --------------------------------------------------------------------------------------
# F5 — the files, on the inspection surface
# --------------------------------------------------------------------------------------


def _assert_the_workshops_files_only_and_signed(world: dict[str, Any], body: dict) -> None:
    ids = {item["id"] for item in body["items"]}
    assert ids == {world["photo"], world["recording"]}, "only the files tagged to this workshop"
    assert body["truncated"] is False
    for item in body["items"]:
        assert item["url"].startswith(SIGNED), "the stored public URL travelled instead of a signature"
        assert "objectKey" not in item and "publicUrl" not in item
        assert "uploadedById" not in item, "the reader payload is a whitelist"


def test_an_inspector_reads_the_files_of_the_workshop_they_inspect(world):
    response = _get(world, "inspector", f"/design-workshop-inspections/{world['inspected']}/media")
    assert response.status_code == 200, response.text
    _assert_the_workshops_files_only_and_signed(world, response.json())


def test_an_inspector_of_another_workshop_is_refused_like_a_missing_id(world):
    response = _get(
        world, "other_inspector", f"/design-workshop-inspections/{world['inspected']}/media"
    )
    assert response.status_code == 404
    assert response.json()["detail"] == "Record not found"


def test_an_inspector_with_no_inspection_at_all_is_refused(world):
    response = _get(world, "idle_inspector", f"/design-workshop-inspections/{world['inspected']}/media")
    assert response.status_code == 404


@pytest.mark.parametrize("slug", ["designer", "professor", "director"])
def test_roles_that_cannot_inspect_are_refused_the_inspection_files(world, slug):
    response = _get(world, slug, f"/design-workshop-inspections/{world['inspected']}/media")
    assert response.status_code == 403


def test_a_ministry_admin_posted_as_director_is_not_thereby_an_inspector(world):
    """Admitted to the inspection SURFACE by role, refused this workshop because no row names them."""
    response = _get(world, "ministry", f"/design-workshop-inspections/{world['inspected']}/media")
    assert response.status_code == 404


# --------------------------------------------------------------------------------------
# F5 — the files, on the oversight surface
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("slug", ["director", "ministry"])
def test_a_post_holder_reads_the_files_of_the_workshop_they_are_posted_to(world, slug):
    response = _get(world, slug, f"/design-workshop-oversight/assigned/{world['inspected']}/media")
    assert response.status_code == 200, response.text
    _assert_the_workshops_files_only_and_signed(world, response.json())


@pytest.mark.parametrize("slug", ["other_director", "idle_ministry"])
def test_a_post_holder_not_posted_to_the_workshop_is_refused(world, slug):
    response = _get(world, slug, f"/design-workshop-oversight/assigned/{world['inspected']}/media")
    assert response.status_code == 404
    assert response.json()["detail"] == "Record not found"


@pytest.mark.parametrize("slug", ["inspector", "designer", "professor"])
def test_roles_that_cannot_hold_a_post_are_refused_the_oversight_files(world, slug):
    response = _get(world, slug, f"/design-workshop-oversight/assigned/{world['inspected']}/media")
    assert response.status_code == 403


def test_neither_files_read_is_a_write_door(world):
    """GET only. A post holder gains no way to change a file through either path."""
    for path in (
        f"/api/design-workshop-inspections/{world['inspected']}/media",
        f"/api/design-workshop-oversight/assigned/{world['inspected']}/media",
    ):
        for method in ("post", "put", "patch", "delete"):
            response = getattr(world["client"], method)(path, headers=_as(world, "director"))
            assert response.status_code == 405, f"{method.upper()} {path}"
    # And the media write door itself still refuses a reader who did not upload the file.
    response = world["client"].delete(f"/api/media/{world['photo']}", headers=_as(world, "inspector"))
    assert response.status_code == 403


def test_the_files_read_does_not_widen_the_general_media_read(world):
    """An inspector still gets no URL for this workshop's file from ``GET /media/{id}``."""
    response = _get(world, "inspector", f"/media/{world['photo']}")
    assert response.status_code == 200
    body = response.json()
    assert "url" not in body and "objectKey" not in body


# --------------------------------------------------------------------------------------
# F5 — the custom questions travel with both reads
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("slug", "path"),
    [
        ("inspector", "/design-workshop-inspections/{id}"),
        ("director", "/design-workshop-oversight/assigned/{id}"),
    ],
)
def test_both_reads_carry_the_workshops_own_questions(world, slug, path):
    response = _get(world, slug, path.format(id=world["inspected"]))
    assert response.status_code == 200, response.text
    sections = response.json()["customSections"]["sections"]
    labels = {field["label"] for section in sections for field in section["fields"]}
    assert "Which mandi buys the cloth?" in labels


# --------------------------------------------------------------------------------------
# F13 — the inspection list's filters
# --------------------------------------------------------------------------------------


def _listed(world: dict[str, Any], slug: str, query: str) -> set[str]:
    response = _get(world, slug, f"/design-workshop-inspections?pageSize=100&{query}")
    assert response.status_code == 200, response.text
    return {row["id"] for row in response.json()["items"]}


def test_the_unfiltered_list_is_the_inspectors_rows(world):
    assert _listed(world, "inspector", f"search={world['stamp']}") == {
        world["inspected"],
        world["second"],
    }


@pytest.mark.parametrize(
    ("query", "expected"),
    [
        ("statusFilter=PRE_SUBMISSION", {"inspected"}),
        ("round=2", {"inspected"}),
        ("round=0", {"second"}),
        ("state=Odisha", {"inspected"}),
        ("workshopKind=DESIGN_PROTOTYPE_DEVELOPMENT", {"inspected"}),
        ("dateFrom=2026-08-01", {"second"}),
        ("dateTo=2026-03-10", {"inspected"}),
        ("dateFrom=2026-03-10&dateTo=2026-08-20", {"inspected", "second"}),
    ],
)
def test_each_filter_narrows_the_inspectors_own_rows(world, query, expected):
    got = _listed(world, "inspector", f"search={world['stamp']}&{query}")
    assert got == {world[key] for key in expected}


def test_no_filter_reaches_a_workshop_the_inspector_holds_no_row_on(world):
    """``elsewhere`` matches every one of these values and is never listed."""
    got = _listed(
        world,
        "inspector",
        f"search={world['stamp']}&statusFilter=NEEDS_REVISION&round=1&state=Gujarat",
    )
    assert got == set()


def test_a_status_outside_the_vocabulary_is_a_422_not_an_empty_list(world):
    response = _get(world, "inspector", "/design-workshop-inspections?statusFilter=draft")
    assert response.status_code == 422


# --------------------------------------------------------------------------------------
# F8 — the pool directory
# --------------------------------------------------------------------------------------


def _directory(world: dict[str, Any], slug: str):
    return _get(world, slug, f"/design-ratings/workshops?pageSize=100&search={world['stamp']}")


@pytest.mark.parametrize("slug", ["designer", "director", "ministry", "admin"])
def test_the_pool_reviewers_see_only_workshops_that_opened_a_piece(world, slug):
    response = _directory(world, slug)
    assert response.status_code == 200, response.text
    rows = response.json()["items"]
    assert [row["workshopId"] for row in rows] == [world["inspected"]]
    (row,) = rows
    assert set(row) == {"workshopId", "title", "startDate", "endDate", "openCounts"}
    assert row["openCounts"] == {"prototype": 2, "sketch": 1}
    served = repr(row)
    assert "Gifting" not in served and "2026-08-10" not in served, "a stage value or the gate leaked"


@pytest.mark.parametrize("slug", ["inspector", "professor"])
def test_the_pool_directory_is_refused_to_everybody_the_pool_round_refuses(world, slug):
    response = _directory(world, slug)
    assert response.status_code == 404
    assert response.json()["detail"] == "Record not found"


def test_a_listed_workshops_pool_round_opens_for_a_stranger(world):
    """Every row listed is a round that answers: the designer holds no grant on ``inspected``."""
    response = _get(
        world,
        "designer",
        f"/design-ratings/rounds/POOL?workshopId={world['inspected']}&entityKey=prototype",
    )
    assert response.status_code in {200, 503}, response.text
