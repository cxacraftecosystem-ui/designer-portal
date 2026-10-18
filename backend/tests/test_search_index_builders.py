"""What one record becomes in the search index (services/search_index; docs/SEARCH.md).

Against a real database: the builders read relations, and the shareable rule reads the cross-portal
link. Every type is built from one seeded world; identity values are seeded on purpose and must
reach no title, subtitle or body.
"""

from __future__ import annotations

import pytest
import pytest_asyncio
from conftest import needs_db
from search_support import SECRETS, every_record, new_stamp, seed

from app.core.db import db
from app.services import search_index

# One event loop for the module: `world` connects `db` once and every test awaits it.
pytestmark = [needs_db, pytest.mark.asyncio(loop_scope="module")]


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def world():
    await db.connect()
    try:
        w = await seed(new_stamp())
        w.docs = {}
        for entity_type, entity_id in every_record(w):
            w.docs[(entity_type, entity_id)] = await search_index.build(entity_type, entity_id)
        yield w
    finally:
        await db.disconnect()


async def test_every_type_builds_a_document(world):
    built = {key: doc for key, doc in world.docs.items() if doc is not None}
    assert {t for t, _ in built} == set(search_index.BUILDERS)
    for (entity_type, entity_id), doc in built.items():
        assert doc["entityType"] == entity_type and doc["entityId"] == entity_id
        assert doc["title"] and len(doc["contentHash"]) == 64
        assert doc["audience"] in ("ALL", "DW")


async def test_no_identity_value_reaches_the_index(world):
    for key, doc in world.docs.items():
        if doc is None:
            continue
        text = " ".join(str(doc.get(f) or "") for f in ("title", "subtitle", "body", "place"))
        for secret in SECRETS:
            assert secret not in text, (key, secret)


async def test_the_identity_answer_is_skipped_and_the_practice_answer_kept(world):
    doc = world.docs[("interviews", world.interview.id)]
    assert "Indigo and madder." in doc["body"]
    assert doc["artisanIds"] == [world.linked.id]


async def test_shareable_follows_the_artisan_link(world):
    docs = world.docs
    assert docs[("artisans", world.linked.id)]["shareable"] is True
    assert docs[("artisans", world.unlinked.id)]["shareable"] is False
    assert docs[("products", world.product_title.id)]["shareable"] is True
    assert docs[("products", world.product_body.id)]["shareable"] is False
    # Named only as free text: never shareable.
    assert docs[("products", world.product_free.id)]["shareable"] is False
    assert docs[("tools", world.tool.id)]["shareable"] is True
    assert docs[("interviews", world.interview.id)]["shareable"] is True
    # Names no artisan.
    assert docs[("crafts", world.craft.id)]["shareable"] is True
    assert docs[("workshops", world.workshop.id)]["shareable"] is True


async def test_design_workshop_data_is_dw_audience_and_never_shared(world):
    docs = world.docs
    for key in (
        ("designWorkshops", world.dw_designer.id),
        ("reports", world.report.id),
        ("transcripts", world.transcript_dw.id),
        ("media", world.transcript_dw.id),
    ):
        assert docs[key]["audience"] == "DW", key
        assert docs[key]["shareable"] is False, key
        assert docs[key]["designWorkshopId"] == world.dw_designer.id, key
    assert docs[("transcripts", world.transcript_open.id)]["audience"] == "ALL"


async def test_stage_text_joins_the_design_workshop_document(world):
    doc = world.docs[("designWorkshops", world.dw_designer.id)]
    assert f"Glossolalia {world.stamp}" in doc["body"]
    assert doc["craftCodes"] == [world.code.code]


async def test_clusters_merge_spellings_from_plan_and_workshop(world):
    doc = world.docs[("clusters", world.cluster_key)]
    assert doc["audience"] == "ALL"
    assert doc["openPath"].startswith("/design-workshops?cluster=")
    assert "Rajasthan" in doc["body"] or "Rajasthan" in (doc["place"] or "")
    assert search_index.cluster_key(f"  Bagru  {world.stamp}  Cluster ") == world.cluster_key


async def test_open_paths_are_stable(world):
    assert world.docs[("artisans", world.linked.id)]["openPath"] == f"/artisans/{world.linked.id}"
    assert world.docs[("reports", world.report.id)]["openPath"] == (
        f"/design-workshops/{world.dw_designer.id}"
    )
    assert search_index.open_path("clusters", "a b/c") == "/design-workshops?cluster=a%20b%2Fc"
    assert search_index.open_path("interviews", "i1") == "/questionnaire/interviews/i1"


async def test_a_transcriptless_file_has_no_transcript_document(world):
    await db.mediafile.update(where={"id": world.transcript_open.id}, data={"transcriptText": ""})
    assert await search_index.build("transcripts", world.transcript_open.id) is None
    assert await search_index.build("media", world.transcript_open.id) is not None


async def test_the_body_is_capped(world):
    await db.mediafile.update(
        where={"id": world.transcript_dw.id}, data={"transcriptText": "indigo " * 30_000}
    )
    doc = await search_index.build("transcripts", world.transcript_dw.id)
    assert len(doc["body"]) == search_index.BODY_LIMIT


async def test_missing_and_unknown_records_build_nothing(world):
    assert await search_index.build("artisans", "does-not-exist") is None
    assert await search_index.build("nonsense", world.linked.id) is None


def test_scrub_removes_identity_shaped_text():
    text = search_index.scrub("Call 98765 01234 or 2341-2341-2346, mail a.b@c.org, year 2026")
    assert "98765" not in text and "2341" not in text and "@" not in text
    assert "2026" in text
