from __future__ import annotations

import asyncio
import contextlib
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

from conftest import needs_db
from query_count_support import asgi_client

from app.core.db import db
from app.core.security import create_access_token
from prisma import Json

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]
BASE = datetime(2026, 4, 1, 9, 0, tzinfo=UTC)
ROOT = "by-design-workshop"


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


async def _seed() -> dict[str, Any]:
    admin = await db.user.create(
        data={"email": f"dw-counts-{STAMP}@example.org", "name": f"DW Counts {STAMP}", "role": "MASTER_ADMIN"}
    )
    workshop = await db.workshop.create(
        data={"title": f"Counts {STAMP}", "date": BASE, "place": "Kharagpur", "createdById": admin.id}
    )
    craft = await db.craft.create(data={"name": f"Tamta {STAMP}"})
    shown = await db.designworkshop.create(
        data={
            "title": f"A {STAMP}",
            "status": "APPROVED",
            "approvedAt": BASE,
            "approvedById": admin.id,
            "approvedRound": 1,
            "craftId": craft.id,
            "workshopId": workshop.id,
            "createdById": admin.id,
        }
    )
    tables = await db.designworkshop.create(
        data={"title": f"B {STAMP}", "status": "IN_PROGRESS", "createdById": admin.id}
    )
    for record in (shown, tables):
        await db.dwstageentry.create(
            data={
                "designWorkshopId": record.id,
                "stageKey": "CLUSTER_CRAFT_BACKGROUND",
                "entityKey": "background",
                "data": Json({}),
            }
        )
    artisan = await db.artisan.create(
        data={"name": f"Kiran {STAMP}", "place": "Almora", "createdById": admin.id, "designWorkshopId": shown.id}
    )
    photo = await db.mediafile.create(
        data={
            "originalFilename": f"{STAMP}-photo.jpg",
            "mediaType": "IMAGE",
            "mimeType": "image/jpeg",
            "sizeBytes": 300,
            "bucket": "test-bucket",
            "objectKey": f"media/{admin.id}/{uuid.uuid4().hex}/photo.jpg",
            "uploadedById": admin.id,
            "designWorkshopId": shown.id,
            "recordedAt": BASE + timedelta(days=1),
            "status": "PENDING",
        }
    )
    voice = await db.mediafile.create(
        data={
            "originalFilename": f"{STAMP}-voice.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 500,
            "bucket": "test-bucket",
            "objectKey": f"media/{admin.id}/{uuid.uuid4().hex}/voice.m4a",
            "uploadedById": admin.id,
            "linkedRecordType": "designWorkshop",
            "linkedRecordId": shown.id,
            "recordedAt": BASE + timedelta(days=2),
            "status": "APPROVED",
            "transcriptText": "Tamta sheets are hammered by hand",
            "transcriptLanguage": "hin",
        }
    )
    return {
        "admin": admin,
        "workshop": workshop,
        "craft": craft,
        "shown": shown,
        "tables": tables,
        "artisan": artisan,
        "media": [photo.id, voice.id],
    }


async def _cleanup(seed: dict[str, Any]) -> None:
    dws = [seed["shown"].id, seed["tables"].id]
    for step in (
        db.mediafile.delete_many(where={"id": {"in": seed["media"]}}),
        db.dwstageentry.delete_many(where={"designWorkshopId": {"in": dws}}),
        db.artisan.delete_many(where={"id": seed["artisan"].id}),
        db.designworkshop.delete_many(where={"id": {"in": dws}}),
        db.craft.delete_many(where={"id": seed["craft"].id}),
        db.workshop.delete_many(where={"id": seed["workshop"].id}),
        db.user.delete_many(where={"id": seed["admin"].id}),
    ):
        with contextlib.suppress(Exception):
            await step


def _cases(seed: dict[str, Any]) -> list[tuple[str, str, dict[str, str]]]:
    a = seed["shown"].id
    return [
        ("mediaType", "IMAGE", {"mediaTypes": "IMAGE"}),
        ("mediaType", "AUDIO", {"mediaTypes": "AUDIO"}),
        ("mediaType", "DOCUMENT", {"mediaTypes": "DOCUMENT"}),
        ("hasTranscript", "yes", {"hasTranscript": "yes"}),
        ("hasTranscript", "no", {"hasTranscript": "no"}),
        ("recordType", "media", {"recordTypes": "media"}),
        ("recordType", "designWorkshops", {"recordTypes": "designWorkshops"}),
        ("status", "APPROVED", {"statuses": "APPROVED"}),
        ("status", "PENDING", {"statuses": "PENDING"}),
        ("designWorkshop", a, {"designWorkshopIds": a}),
        ("designWorkshop", seed["tables"].id, {"designWorkshopIds": seed["tables"].id}),
        ("craft", seed["craft"].id, {"craftIds": seed["craft"].id}),
        ("workshop", seed["workshop"].id, {"workshopIds": seed["workshop"].id}),
        ("artisan", seed["artisan"].id, {"artisanIds": seed["artisan"].id}),
        ("recordedBy", seed["admin"].id, {"recordedByIds": seed["admin"].id}),
        ("language", "hin", {"languages": "hin"}),
    ]


def _folders(tree: dict[str, Any]) -> dict[str, int]:
    return {e["path"].rsplit("/", 1)[-1]: e["count"] for e in tree["entries"] if e["kind"] == "folder"}


def _option(facets: dict[str, Any], facet: str, value: str) -> int:
    found = next(f for f in facets["facets"] if f["facet"] == facet)
    return next(o["count"] for o in found["options"] if o["value"] == value)


def test_every_filter_gives_the_same_count_to_the_tree_header_tabs_and_facets():
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = {"Authorization": f"Bearer {create_access_token(subject=seed['admin'].id)}"}
            out: dict[str, Any] = {"seed": seed, "cases": {}}
            async with asgi_client() as client:

                async def get(url: str, **params: Any) -> Any:
                    answer = await client.get(url, params=params, headers=headers)
                    assert answer.status_code == 200, (url, params, answer.text)
                    return answer.json()

                async def look(params: dict[str, str]) -> dict[str, Any]:
                    tree = await get("/api/data/tree", path=ROOT, **params)
                    folders = {
                        key: (await get("/api/data/stats", path=f"{ROOT}/{key}", **params))["records"]
                        for key in _folders(tree)
                    }
                    transcripts = await get("/api/data/transcripts/list", path=ROOT, **params)
                    return {
                        "tree": tree,
                        "header": await get("/api/data/stats", path=ROOT, **params),
                        "folders": folders,
                        "transcripts": transcripts["total"],
                    }

                out["plain"] = await look({})
                mine = {"designWorkshopIds": f"{seed['shown'].id},{seed['tables'].id}"}
                out["facets"] = await get("/api/data/filters")
                out["facets_mine"] = await get("/api/data/filters", **mine)
                for facet, value, params in _cases(seed):
                    scoped = params if facet == "designWorkshop" else {**mine, **params}
                    out["cases"][(facet, value)] = await look(scoped)
                out["report"] = await get(
                    "/api/data/report", path=ROOT, format="json", designWorkshopIds=seed["tables"].id
                )
            return out
        finally:
            await _cleanup(seed)

    seen = _private(work)
    seed = seen["seed"]
    a, b = seed["shown"].id, seed["tables"].id

    plain = seen["plain"]
    folders = _folders(plain["tree"])
    text_a = plain["folders"][a] - 2
    text_b = plain["folders"][b]
    assert text_a >= 2 and text_b >= 2
    assert folders == plain["folders"]
    assert sum(folders.values()) == plain["header"]["records"]
    assert plain["header"]["records"] == plain["header"]["files"] - plain["header"]["byType"]["transcripts"]["files"]
    assert _option(seen["facets"], "designWorkshop", b) == text_b
    assert _option(seen["facets"], "designWorkshop", a) == text_a + 2

    expected = {
        ("mediaType", "IMAGE"): 1,
        ("mediaType", "AUDIO"): 1,
        ("mediaType", "DOCUMENT"): text_a + text_b,
        ("hasTranscript", "yes"): 1,
        ("hasTranscript", "no"): 1,
        ("recordType", "media"): 2,
        ("recordType", "designWorkshops"): text_a + text_b + 2,
        ("status", "APPROVED"): text_a + 1,
        ("status", "PENDING"): 1,
        ("designWorkshop", a): text_a + 2,
        ("designWorkshop", b): text_b,
        ("craft", seed["craft"].id): text_a,
        ("workshop", seed["workshop"].id): text_a,
        ("artisan", seed["artisan"].id): text_a,
        ("recordedBy", seed["admin"].id): 2,
        ("language", "hin"): 1,
    }
    for key, want in expected.items():
        case = seen["cases"][key]
        tree = _folders(case["tree"])
        mine = {k: v for k, v in tree.items() if k in (a, b)}
        assert sum(mine.values()) == sum(count for count in tree.values() if count > 0), (key, tree)
        assert tree == case["folders"], (key, tree, case["folders"])
        assert sum(tree.values()) == case["header"]["records"], (key, tree, case["header"])
        assert sum(mine.values()) == want, (key, mine, want)
        facets = seen["facets"] if key[0] == "designWorkshop" else seen["facets_mine"]
        assert set(tree) <= {a, b}, (key, tree)
        assert _option(facets, *key) == sum(tree.values()), (key, _option(facets, *key), tree)

    assert seen["cases"][("hasTranscript", "yes")]["transcripts"] == 1
    assert seen["cases"][("mediaType", "IMAGE")]["transcripts"] == 0
    assert set(_folders(seen["cases"][("mediaType", "IMAGE")]["tree"])) == {a}
    assert set(_folders(seen["cases"][("designWorkshop", b)]["tree"])) == {b}
    assert seen["cases"][("recordType", "media")]["header"]["byType"]["text"]["files"] == 0

    sheets = {s["name"]: s for s in seen["report"]["sheets"]}
    progress = repr(sheets["Design workshop progress"]["rows"])
    assert f"B {STAMP}" in progress and f"A {STAMP}" not in progress
