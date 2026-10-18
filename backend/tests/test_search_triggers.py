"""The ``search_enqueue()`` triggers and the indexer that drains them (docs/SEARCH.md).

Against a real database, because the behaviour under test is Postgres's: insert and update list the
record in ``SearchIndexQueue``, a delete removes its document in the same statement, a stage row
lists its workshop, a link row lists its artisan, and the drain expands an artisan to the records
whose ``shareable`` depends on it.

The queue is shared by every module that writes records, and nothing drains it while tests run, so
each test empties it first; it holds nothing a test database needs.
"""

from __future__ import annotations

import asyncio

import pytest
import pytest_asyncio
from conftest import needs_db
from search_support import document, index_world, new_stamp, seed

from app.core.db import db
from app.services import search_indexer

# One event loop for the module: `world` connects `db` once and every test awaits it.
pytestmark = [needs_db, pytest.mark.asyncio(loop_scope="module")]


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def world():
    await db.connect()
    try:
        w = await seed(new_stamp())
        await index_world(w)
        yield w
    finally:
        await db.disconnect()


async def _clear_queue() -> None:
    await db.execute_raw('DELETE FROM "SearchIndexQueue"')


async def _queued() -> set[tuple[str, str]]:
    rows = await db.query_raw('SELECT "entityType", "entityId" FROM "SearchIndexQueue"')
    return {(r["entityType"], r["entityId"]) for r in rows}


async def _drain_all() -> None:
    while await search_indexer.drain(batch=500):
        pass


async def test_insert_and_update_enqueue_the_record(world):
    await _clear_queue()
    artisan = await db.artisan.create(
        data={
            "name": f"New artisan {world.stamp}",
            "place": "Bagru",
            "createdById": world.professor.id,
        }
    )
    assert ("artisans", artisan.id) in await _queued()
    await _clear_queue()
    await db.artisan.update(where={"id": artisan.id}, data={"name": f"Renamed {world.stamp}"})
    assert ("artisans", artisan.id) in await _queued()
    await _drain_all()
    doc = await document("artisans", artisan.id)
    assert doc is not None and doc["title"] == f"Renamed {world.stamp}"


async def test_a_delete_removes_the_document_at_once(world):
    craft = await db.craft.create(data={"name": f"Short lived {world.stamp}"})
    await search_indexer.index_one("crafts", craft.id)
    assert await document("crafts", craft.id) is not None
    await _clear_queue()
    await db.craft.delete(where={"id": craft.id})
    assert await document("crafts", craft.id) is None
    assert ("crafts", craft.id) not in await _queued()


async def test_a_stage_change_enqueues_its_workshop(world):
    await _clear_queue()
    await db.dwstageentry.update(
        where={"id": world.stage.id}, data={"searchText": f"Rewritten {world.stamp} riverbank"}
    )
    assert ("designWorkshops", world.dw_designer.id) in await _queued()
    await _drain_all()
    doc = await document("designWorkshops", world.dw_designer.id)
    assert f"Rewritten {world.stamp}" in doc["body"]


async def test_a_plan_row_enqueues_its_cluster_by_key(world):
    await _clear_queue()
    await db.annualplanentry.update(where={"id": world.plan.id}, data={"notes": "touched"})
    assert ("clusters", world.cluster_key) in await _queued()


async def test_a_link_change_reaches_the_artisan_and_its_dependents(world):
    assert (await document("products", world.product_body.id))["shareable"] is False
    await _clear_queue()
    await db.crossportalartisanlink.create(
        data={
            "artisanId": world.unlinked.id,
            "remoteArtisanId": f"fr2-{world.stamp}",
            "matchedBy": "PEHCHAN",
        }
    )
    assert ("artisans", world.unlinked.id) in await _queued()
    await _drain_all()
    assert (await document("artisans", world.unlinked.id))["shareable"] is True
    assert (await document("products", world.product_body.id))["shareable"] is True

    await db.crossportalartisanlink.delete(where={"artisanId": world.unlinked.id})
    await _drain_all()
    assert (await document("artisans", world.unlinked.id))["shareable"] is False
    assert (await document("products", world.product_body.id))["shareable"] is False


async def test_soft_deleting_a_design_workshop_removes_its_document(world):
    from datetime import UTC, datetime

    assert await document("designWorkshops", world.dw_professor.id) is not None
    await _clear_queue()
    await db.designworkshop.update(
        where={"id": world.dw_professor.id}, data={"deletedAt": datetime.now(UTC)}
    )
    await _drain_all()
    assert await document("designWorkshops", world.dw_professor.id) is None


async def test_reindex_all_queues_one_type(world):
    await _clear_queue()
    queued = await search_indexer.reindex_all("crafts")
    assert queued >= 2
    assert {t for t, _ in await _queued()} == {"crafts"}
    with pytest.raises(ValueError):
        await search_indexer.reindex_all("nonsense")
    await _clear_queue()


async def test_a_drain_keeps_what_it_read_until_it_has_built_it(world, monkeypatch):
    await _clear_queue()
    await search_indexer.enqueue("crafts", world.craft.id)

    async def dies(*_a, **_k):
        raise asyncio.CancelledError  # the worker killed mid-batch

    monkeypatch.setattr(search_indexer, "index_one", dies)
    with pytest.raises(asyncio.CancelledError):
        await search_indexer.drain()
    assert ("crafts", world.craft.id) in await _queued()
    monkeypatch.undo()
    await _drain_all()
    assert await _queued() == set()


async def test_a_failed_build_is_queued_again_and_the_rest_cleared(world, monkeypatch):
    await _clear_queue()
    await search_indexer.enqueue("crafts", world.craft.id)
    await search_indexer.enqueue("crafts", world.other_craft.id)
    real = search_indexer.index_one

    async def one_fails(entity_type, entity_id):
        if entity_id == world.craft.id:
            raise RuntimeError("build failed")
        return await real(entity_type, entity_id)

    monkeypatch.setattr(search_indexer, "index_one", one_fails)
    assert await search_indexer.drain() == 2
    assert await _queued() == {("crafts", world.craft.id)}
    monkeypatch.undo()
    await _drain_all()


async def test_compaction_keeps_one_row_per_record(world):
    await _clear_queue()
    for _ in range(3):
        await search_indexer.enqueue("crafts", world.craft.id)
    await search_indexer.enqueue("tools", world.tool.id)
    assert await search_indexer.compact_queue() == 2
    rows = await db.query_raw('SELECT count(*)::int AS n FROM "SearchIndexQueue"')
    assert rows[0]["n"] == 2
    assert await _queued() == {("crafts", world.craft.id), ("tools", world.tool.id)}
    await _clear_queue()


async def test_a_file_on_a_product_follows_the_products_artisan(world):
    await _clear_queue()
    media = await db.mediafile.create(
        data={
            "originalFilename": f"product-photo-{world.stamp}.jpg",
            "objectKey": f"search/{world.stamp}/product-photo.jpg",
            "mediaType": "IMAGE",
            "mimeType": "image/jpeg",
            "sizeBytes": 2048,
            "bucket": "test-bucket",
            "uploadedById": world.professor.id,
            "productId": world.product_title.id,
        }
    )
    try:
        await _drain_all()
        assert (await document("media", media.id))["shareable"] is True
        # The product moves to an artisan who has not agreed: the file is rebuilt with it.
        await db.productdocumentation.update(
            where={"id": world.product_title.id}, data={"artisanId": world.unlinked.id}
        )
        await _drain_all()
        assert (await document("media", media.id))["shareable"] is False
    finally:
        await db.mediafile.delete(where={"id": media.id})
        await db.productdocumentation.update(
            where={"id": world.product_title.id}, data={"artisanId": world.linked.id}
        )
        await _drain_all()


async def test_an_interview_artisan_link_enqueues_its_interview(world):
    await _clear_queue()
    await db.questionnaireinterviewartisan.create(
        data={"interviewId": world.interview.id, "artisanId": world.unlinked.id}
    )
    assert ("interviews", world.interview.id) in await _queued()
    await _drain_all()
    assert (await document("interviews", world.interview.id))["shareable"] is False
    await db.questionnaireinterviewartisan.delete_many(
        where={"interviewId": world.interview.id, "artisanId": world.unlinked.id}
    )
    assert ("interviews", world.interview.id) in await _queued()
    await _drain_all()
    assert (await document("interviews", world.interview.id))["shareable"] is True
