import os
import uuid
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.core.db import db
from app.core.security import hash_password

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
        "email": f"reads-{key}-{stamp}@example.org", "name": f"Reads {key.title()}",
        "role": role, "passwordHash": hash_password("unused"),
    })


async def _file(uploader_id: str, stamp: str, name: str, **extra):
    key = f"media/{uploader_id}/{stamp}{uuid.uuid4().hex[:6]}/{name}"
    return await db.mediafile.create(data={
        "originalFilename": name, "mediaType": "AUDIO", "mimeType": "audio/mpeg", "sizeBytes": 4096,
        "bucket": "test-bucket", "objectKey": key, "url": f"https://cdn.example.test/{key}",
        "uploadedById": uploader_id, **extra,
    })


@pytest.fixture(scope="module")
async def world():
    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        w = SimpleNamespace(stamp=stamp)
        w.sharer = await _person(stamp, "sharer", "DESIGNER")
        w.grantee = await _person(stamp, "grantee", "RESEARCHER")
        w.stranger = await _person(stamp, "stranger", "DESIGNER")
        w.admin = await _person(stamp, "admin", "ADMIN")
        granted = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Ramesh Kumar",
            "productName": f"Granted yardage {stamp}", "createdById": w.sharer.id,
        })
        private = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Sita Devi",
            "productName": f"Private yardage {stamp}", "createdById": w.sharer.id,
        })
        w.granted_file = await _file(w.sharer.id, stamp, "granted.mp3", productId=granted.id)
        w.private_file = await _file(w.sharer.id, stamp, "private.mp3", productId=private.id)
        grant = await db.dataaccessgrant.create(data={
            "ownerId": w.sharer.id, "granteeId": w.grantee.id, "tier": "DOWNLOAD", "status": "GRANTED", "allData": False,
        })
        await db.dataaccessscopeitem.create(data={"grantId": grant.id, "recordType": "product", "recordId": granted.id})
        w.open_workshop = await db.workshop.create(data={
            "title": f"Open workshop {stamp}", "date": datetime(2026, 6, 20, tzinfo=UTC), "place": "Bagru",
            "createdById": w.admin.id,
        })
        await db.workshopassignment.create(data={
            "workshopId": w.open_workshop.id, "userId": w.stranger.id, "status": "DENIED", "accessLevel": "CONTRIBUTE",
        })
        await db.workshopassignment.create(data={
            "workshopId": w.open_workshop.id, "userId": w.grantee.id, "status": "GRANTED", "accessLevel": "VIEW",
        })
        w.removed = await _file(
            w.sharer.id, stamp, f"removed-{stamp}.mp3",
            transcriptText=f"words that were removed {stamp}", removedAt=datetime.now(UTC),
        )
    finally:
        await db.disconnect()
    yield w


@pytest.fixture
async def connected():
    await db.connect()
    try:
        yield db
    finally:
        await db.disconnect()


async def test_owned_or_granted_where_follows_the_grant_scope(world, connected):
    from app.services.records import owned_or_granted_where

    where = await owned_or_granted_where(world.grantee, owner_field="uploadedById")
    rows = await db.mediafile.find_many(
        where={"AND": [where, {"id": {"in": [world.granted_file.id, world.private_file.id]}}]}
    )
    assert {row.id for row in rows} == {world.granted_file.id}


async def test_media_url_owners_names_only_the_scoped_files(world, connected):
    from app.services.records import media_url_allows, media_url_owners

    owners = await media_url_owners(world.grantee)
    assert media_url_allows(owners, world.granted_file)
    assert not media_url_allows(owners, world.private_file)


async def test_a_denied_or_view_only_member_of_an_open_workshop_cannot_contribute(world, connected):
    from app.services.workshop_access import resolve_workshop_access

    denied = await resolve_workshop_access(world.stranger, world.open_workshop.id)
    view_only = await resolve_workshop_access(world.grantee, world.open_workshop.id)
    anyone = await resolve_workshop_access(world.admin, world.open_workshop.id)
    assert not denied.at_least("CONTRIBUTE")
    assert not view_only.at_least("CONTRIBUTE") and view_only.at_least("VIEW")
    assert anyone.at_least("CONTRIBUTE")


async def test_removed_media_and_its_transcript_leave_the_search_index(world, connected):
    from app.services import search_index

    assert await search_index.build("media", world.removed.id) is None
    assert await search_index.build("transcripts", world.removed.id) is None


async def test_the_map_filters_leave_out_removed_media(world, connected):
    import json

    from app.api.routes.map_points import map_points

    answer = await map_points(
        current_user=world.admin, q=f"removed-{world.stamp}", craftId=None, place=None, artisanId=None,
        mediaType=None, types=["media"], dateFrom=None, dateTo=None, workshopIds=None, level=None,
        focusType=None, focusId=None,
    )
    assert world.removed.id not in json.dumps(answer, default=str)
    assert sum(int(v or 0) for v in (_find_by_type(answer) or {}).values()) == 0


def _find_by_type(node):
    if isinstance(node, dict):
        if "byType" in node and isinstance(node["byType"], dict):
            return node["byType"]
        for value in node.values():
            found = _find_by_type(value)
            if found is not None:
                return found
    return None



async def test_a_removed_recording_has_no_readable_transcript(world, connected):
    from app.services import transcript_portal

    assert await transcript_portal.readable_ids([world.removed], world.admin) == set()
