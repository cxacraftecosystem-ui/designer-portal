import os
import uuid
from datetime import UTC, datetime

import pytest

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


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


async def _person(stamp: str, key: str, role: str):
    return await db.user.create(data={
        "email": f"gate-{key}-{stamp}@example.org", "name": f"Gate {key.title()}",
        "role": role, "passwordHash": hash_password("unused"),
    })


async def _photo(uploader_id: str, stamp: str, name: str, **links):
    key = f"media/{uploader_id}/{stamp}{uuid.uuid4().hex[:6]}/{name}"
    return await db.mediafile.create(data={
        "originalFilename": name, "mediaType": "IMAGE", "mimeType": "image/jpeg", "sizeBytes": 4096,
        "bucket": "test-bucket", "objectKey": key, "url": f"https://cdn.example.test/{key}",
        "uploadedById": uploader_id, **links,
    })


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        sharer = await _person(stamp, "sharer", "DESIGNER")
        grantee = await _person(stamp, "grantee", "RESEARCHER")
        stranger = await _person(stamp, "stranger", "DESIGNER")
        volunteer = await _person(stamp, "volunteer", "CROWDSOURCE_VOLUNTEER")
        reviewer = await _person(stamp, "reviewer", "RESEARCHER")
        admin = await _person(stamp, "admin", "ADMIN")
        assigned = await _person(stamp, "assigned", "DESIGNER")

        in_scope = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Ramesh Kumar",
            "productName": f"Granted yardage {stamp}", "createdById": sharer.id,
        })
        out_of_scope = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Sita Devi",
            "productName": f"Private yardage {stamp}", "createdById": sharer.id,
        })
        granted_photo = await _photo(sharer.id, stamp, f"granted-{stamp}.jpg", productId=in_scope.id)
        private_photo = await _photo(sharer.id, stamp, f"private-{stamp}.jpg", productId=out_of_scope.id)
        loose_photo = await _photo(sharer.id, stamp, f"loose-{stamp}.jpg")
        grant = await db.dataaccessgrant.create(data={
            "ownerId": sharer.id, "granteeId": grantee.id, "tier": "DOWNLOAD", "status": "GRANTED", "allData": False,
        })
        await db.dataaccessscopeitem.create(data={"grantId": grant.id, "recordType": "product", "recordId": in_scope.id})

        pending = await db.artisan.create(data={
            "name": f"Pending Artisan {stamp}", "place": "Bagru", "createdById": volunteer.id, "status": "PENDING",
        })

        design_workshop = await db.designworkshop.create(data={"title": f"Closed studio {stamp}", "createdById": admin.id})

        curated = await db.workshop.create(data={
            "title": f"Curated field workshop {stamp}", "date": datetime(2026, 6, 20, tzinfo=UTC),
            "place": "Bagru", "createdById": admin.id,
        })
        await db.workshopassignment.create(data={
            "workshopId": curated.id, "userId": assigned.id, "status": "GRANTED", "accessLevel": "CONTRIBUTE",
            "assignedById": admin.id,
        })
        curated_artisan = await db.artisan.create(data={
            "name": f"Curated Artisan {stamp}", "place": "Bagru", "createdById": admin.id, "workshopId": curated.id,
        })

        open_workshop = await db.workshop.create(data={
            "title": f"Open field workshop {stamp}", "date": datetime(2026, 6, 20, tzinfo=UTC),
            "place": "Bagru", "createdById": admin.id,
        })
        await db.workshopassignment.create(data={
            "workshopId": open_workshop.id, "userId": stranger.id, "status": "DENIED", "accessLevel": "CONTRIBUTE",
        })

        removed = await _photo(sharer.id, stamp, f"removed-{stamp}.jpg")
        removed = await db.mediafile.update(
            where={"id": removed.id},
            data={"removedAt": datetime.now(UTC), "transcriptText": f"words that were removed {stamp}"},
        )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "stamp": stamp,
            "tokens": {
                name: create_access_token(subject=person.id)
                for name, person in {
                    "sharer": sharer, "grantee": grantee, "stranger": stranger, "volunteer": volunteer,
                    "reviewer": reviewer, "admin": admin,
                }.items()
            },
            "ids": {
                "sharer": sharer.id, "grantee": grantee.id, "stranger": stranger.id,
                "granted_photo": granted_photo.id, "private_photo": private_photo.id,
                "private_name": private_photo.originalFilename, "loose_name": loose_photo.originalFilename,
                "pending": pending.id, "design_workshop": design_workshop.id,
                "curated_artisan": curated_artisan.id, "open_workshop": open_workshop.id,
                "removed": removed.id,
            },
        }


def _as(env, who: str) -> dict:
    return {"Authorization": f"Bearer {env['tokens'][who]}"}


def _complete_body(env, who_id: str, **links) -> dict:
    name = f"gate-{uuid.uuid4().hex[:8]}.jpg"
    return {
        "originalFilename": name, "mediaType": "IMAGE", "mimeType": "image/jpeg", "sizeBytes": 2048,
        "objectKey": f"media/{who_id}/{uuid.uuid4().hex}/{name}", **links,
    }


@pytest.fixture
def no_storage(monkeypatch):
    import app.api.routes.media as media_routes
    from app.services import s3

    monkeypatch.setattr(s3, "head_object", lambda key: None)
    monkeypatch.setattr(media_routes, "head_object", lambda key: None, raising=False)


def test_a_subset_grant_does_not_release_the_owners_other_media(env):
    client = env["client"]
    private = client.get(f"/api/media/{env['ids']['private_photo']}", headers=_as(env, "grantee"))
    assert private.status_code == 200, private.text
    body = private.json()
    assert not (set(body) & {"url", "publicUrl", "objectKey"}), "a one-product grant released an out-of-scope photo"
    granted = client.get(f"/api/media/{env['ids']['granted_photo']}", headers=_as(env, "grantee"))
    assert granted.status_code == 200, granted.text
    assert granted.json().get("url"), "the photo the grant names must still be served"


def test_the_owner_export_under_a_subset_grant_leaves_out_the_owners_other_media(env):
    response = env["client"].get(
        f"/api/export/dataset?ownerId={env['ids']['sharer']}", headers=_as(env, "grantee")
    )
    assert response.status_code == 200, response.text
    assert env["ids"]["private_name"] not in response.text
    assert env["ids"]["loose_name"] not in response.text, "the owner's unlinked files rode along on a one-product grant"


def test_a_decided_record_is_not_re_decided_by_a_lower_reviewer(env):
    client = env["client"]
    record = env["ids"]["pending"]
    approved = client.post(f"/api/review/artisan/{record}/approve", json={"notes": "Checked."}, headers=_as(env, "admin"))
    assert approved.status_code == 200, approved.text
    overturned = client.post(f"/api/review/artisan/{record}/reject", json={"notes": "No."}, headers=_as(env, "reviewer"))
    assert overturned.status_code == 409, overturned.text
    again = client.post(f"/api/review/artisan/{record}/approve", json={"notes": "Again."}, headers=_as(env, "admin"))
    assert again.status_code == 409, again.text
    stands = client.get(f"/api/artisans/{record}", headers=_as(env, "admin"))
    assert stands.status_code == 200, stands.text
    assert stands.json()["status"] == "APPROVED"


def test_a_design_workshop_tag_needs_the_workshop_edit_check(env, no_storage):
    body = _complete_body(
        env, env["ids"]["stranger"], linkedRecordType="designWorkshop", linkedRecordId=env["ids"]["design_workshop"]
    )
    response = env["client"].post("/api/media/complete", json=body, headers=_as(env, "stranger"))
    assert response.status_code in (403, 404), response.text


def test_media_filed_under_a_curated_workshops_artisan_is_checked_against_that_workshop(env, no_storage):
    body = _complete_body(
        env, env["ids"]["stranger"], linkedRecordType="artisan", linkedRecordId=env["ids"]["curated_artisan"]
    )
    response = env["client"].post("/api/media/complete", json=body, headers=_as(env, "stranger"))
    assert response.status_code == 403, response.text
