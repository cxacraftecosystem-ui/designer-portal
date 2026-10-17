"""C9 — start a stage from an earlier workshop.

Owner decision (2026-10-17): copy from the designer's OWN earlier workshops (source marked);
other designers' text only as a read-only reference block; copied text that has not been edited
does NOT count toward completeness until edited or confirmed.

The pure half needs no database. The rest follows ``tests/test_sanction_orders.py``'s convention.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401 - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import workshop_copy
from app.services.design_workshops import workshop_completeness
from app.services.stage_schema import stage
from prisma import Json

STAGE = "CLUSTER_CRAFT_BACKGROUND"
ENTITY = "clusterBackground"


# --------------------------------------------------------------------------------------
# Pure
# --------------------------------------------------------------------------------------


def test_only_the_four_narrative_stages_are_copyable() -> None:
    assert {
        "INTRODUCTORY_ADMIN_DOCUMENTATION",
        "CLUSTER_CRAFT_BACKGROUND",
        "TRADITIONAL_PROCESS_BASELINE",
        "SURVEY_PLANNING",
    } == workshop_copy.COPYABLE_STAGES
    assert workshop_copy.copyable_fields(stage("WORKSHOP_SETUP")) == ()


def test_a_hydrated_box_is_never_offered_for_copying() -> None:
    keys = {field.key for field in workshop_copy.copyable_fields(stage(STAGE))}
    assert {"clusterIntroduction", "craftIntroduction", "history"} <= keys
    for field in workshop_copy.copyable_fields(stage(STAGE)):
        assert field.type.value in {"TEXT", "LONG_TEXT", "RICH_TEXT", "TAGS"}


def test_marking_and_confirming_a_copy() -> None:
    user = SimpleNamespace(id="u1", name="Asha")
    at = datetime(2026, 10, 17, tzinfo=UTC)
    marked = workshop_copy.mark_provenance(
        {}, {"history": {"workshopId": "w0", "title": "Earlier"}}, [], user, now=at
    )
    assert marked["history"]["source"] == workshop_copy.SOURCE_COPIED
    assert workshop_copy.unconfirmed_copies(marked) == {"history"}
    kept = workshop_copy.mark_provenance(marked, {}, ["history"], user, now=at)
    assert kept["history"]["source"] == "designer"
    assert kept["history"]["confirmedCopyOf"] == {"workshopId": "w0", "title": "Earlier"}
    assert workshop_copy.unconfirmed_copies(kept) == set()


def test_a_kept_copy_is_not_re_marked_by_a_repeated_claim() -> None:
    """A second device (or an older client) re-sending the mark must not undo "Keep as is"."""
    user = SimpleNamespace(id="u1", name="Asha")
    source = {"workshopId": "w0", "title": "Earlier"}
    marked = workshop_copy.mark_provenance({}, {"history": source}, [], user)
    kept = workshop_copy.mark_provenance(marked, {}, ["history"], user)
    again = workshop_copy.mark_provenance(kept, {"history": source}, [], user)
    assert again["history"]["source"] == "designer"
    # A copy from a DIFFERENT earlier workshop is a new copy, and is marked.
    other = workshop_copy.mark_provenance(kept, {"history": {"workshopId": "w9", "title": "X"}}, [], user)
    assert other["history"]["source"] == workshop_copy.SOURCE_COPIED


def test_confirm_copied_carries_the_in_flight_frozen_guard() -> None:
    from pathlib import Path

    route = (
        Path(workshop_copy.__file__).resolve().parents[1] / "api" / "routes" / "design_workshop_copy.py"
    ).read_text(encoding="utf-8")
    body = route[route.index("async def confirm_copied_fields(") :]
    assert '"NOT": design_workshop_approvals.FROZEN_WHERE' in body
    assert "db.tx()" in body
    assert '"version": row.version' in body


def test_unconfirmed_copied_text_does_not_count_toward_completeness() -> None:
    data = {
        "clusterIntroduction": "A long history",
        "craftIntroduction": "Ajrakh",
        "history": "Old",
    }
    copied = {"history": {"source": workshop_copy.SOURCE_COPIED, "by": "u1"}}
    row = SimpleNamespace(stageKey=STAGE, entityKey=ENTITY, data=data, fieldProvenance=copied)
    missing = workshop_completeness([row])[STAGE]["missing"]
    assert "History of the craft" in missing
    row.fieldProvenance = {"history": {"source": "designer", "by": "u1"}}
    assert "History of the craft" not in workshop_completeness([row])[STAGE]["missing"]


def test_a_non_copyable_stage_is_scored_as_it_always_was() -> None:
    data = {"anything": "x"}
    assert (
        workshop_copy.counted_data(
            "WORKSHOP_SETUP", "workshopSetup", data, {"anything": {"source": "copied"}}
        )
        == data
    )


# --------------------------------------------------------------------------------------
# Against Postgres
# --------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def world() -> Any:
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    facts: dict[str, Any] = {"stamp": stamp}
    cluster = f"Copy cluster {stamp}"

    async def seed() -> None:
        await db.connect()
        try:
            for slug, role in (("author", "ADMIN"), ("other", "ADMIN"), ("outsider", "DESIGNER")):
                email = f"c9-{slug}-{stamp}@example.org"
                facts[slug] = await db.user.create(
                    data={
                        "email": email,
                        "name": f"C9 {slug} {stamp}",
                        "role": role,
                        "passwordHash": hash_password("c9-password-123"),
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": email,
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": datetime.now(UTC),
                        "notes": "Seeded by tests/test_workshop_copy.py.",
                    }
                )
            for name, owner, status in (
                ("earlier", "author", "IN_PROGRESS"),
                ("current", "author", "DRAFT"),
                ("theirs", "other", "COMPLETE"),
                ("approved", "author", "APPROVED"),
            ):
                facts[name] = await db.designworkshop.create(
                    data={
                        "title": f"C9 {name} {stamp}",
                        "templateId": "DCH_STANDARD",
                        "createdById": facts[owner].id,
                        "clusterName": cluster,
                        "status": status,
                        **({"approvedAt": datetime.now(UTC)} if status == "APPROVED" else {}),
                    }
                )
            await db.dwstageentry.create(
                data={
                    "designWorkshopId": facts["theirs"].id,
                    "stageKey": STAGE,
                    "entityKey": ENTITY,
                    "data": Json({"history": f"Their history {stamp}"}),
                }
            )
            # A frozen report still holding an unchecked copy: keeping it would move completeness.
            await db.dwstageentry.create(
                data={
                    "designWorkshopId": facts["approved"].id,
                    "stageKey": STAGE,
                    "entityKey": ENTITY,
                    "data": Json({"history": f"Our history {stamp}"}),
                    "fieldProvenance": Json(
                        {"history": {"source": workshop_copy.SOURCE_COPIED, "by": facts["author"].id}}
                    ),
                }
            )
        finally:
            await db.disconnect()

    asyncio.run(seed())

    def headers(slug: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {create_access_token(subject=facts[slug].id)}"}

    def put(workshop: str, data: dict[str, Any], **extra: Any):
        return client.put(
            f"/api/design-workshops/{facts[workshop].id}/stages/{STAGE}",
            json={
                "entries": [{"entityKey": ENTITY, "data": data}],
                "replaceCollections": False,
                **extra,
            },
            headers=headers("author"),
        )

    def read(workshop: str):
        return client.get(
            f"/api/design-workshops/{facts[workshop].id}/stages/{STAGE}", headers=headers("author")
        )

    with TestClient(app) as client:
        facts["seeded_earlier"] = put("earlier", {"history": f"Our history {stamp}"})
        facts["sources"] = client.get(
            f"/api/design-workshops/{facts['current'].id}/stages/{STAGE}/copy-sources",
            headers=headers("author"),
        )
        own = facts["sources"].json().get("own", []) if facts["sources"].status_code == 200 else []
        copied_value = own[0]["values"]["history"] if own else None
        facts["copied_value"] = copied_value
        facts["copy_save"] = put(
            "current",
            {"history": copied_value, "craftIntroduction": f"Typed by hand {stamp}"},
            copiedFrom={"history": facts["earlier"].id, "craftIntroduction": facts["earlier"].id},
        )
        facts["after_copy"] = read("current")
        facts["confirm"] = client.post(
            f"/api/design-workshops/{facts['current'].id}/stages/{STAGE}/confirm-copied",
            json={"fields": ["history"]},
            headers=headers("author"),
        )
        facts["after_confirm"] = read("current")
        # The same copy mark arriving again from a second device after "Keep as is".
        facts["resent"] = put(
            "current",
            {"history": copied_value, "craftIntroduction": f"Typed by hand {stamp}"},
            copiedFrom={"history": facts["earlier"].id},
        )
        facts["after_resend"] = read("current")
        facts["confirm_frozen"] = client.post(
            f"/api/design-workshops/{facts['approved'].id}/stages/{STAGE}/confirm-copied",
            json={"fields": ["history"]},
            headers=headers("author"),
        )
        facts["other_sources"] = client.get(
            f"/api/design-workshops/{facts['current'].id}/stages/{STAGE}/copy-sources",
            headers=headers("outsider"),
        )
    yield facts


@needs_db
def test_the_designers_own_text_is_offered_and_others_only_as_reference(world: Any) -> None:
    assert world["seeded_earlier"].status_code == 200, world["seeded_earlier"].text
    assert world["sources"].status_code == 200, world["sources"].text
    body = world["sources"].json()
    assert [source["workshopId"] for source in body["own"]] == [world["earlier"].id]
    assert [source["workshopId"] for source in body["reference"]] == [world["theirs"].id]
    assert body["reference"][0]["values"]["history"] == f"Their history {world['stamp']}"


@needs_db
def test_a_verified_copy_is_marked_and_not_counted(world: Any) -> None:
    assert world["copy_save"].status_code == 200, world["copy_save"].text
    stage_read = world["after_copy"].json()
    stamps = stage_read["provenance"]["singleton"]
    assert stamps["history"]["source"] == workshop_copy.SOURCE_COPIED
    assert stamps["history"]["copiedFrom"]["workshopId"] == world["earlier"].id
    # A claim on text that is NOT the source's is dropped: that box is the designer's own.
    assert stamps["craftIntroduction"]["source"] == "designer"
    assert "History of the craft" in stage_read["completeness"]["missing"]
    assert "Craft introduction" not in stage_read["completeness"]["missing"]


@needs_db
def test_keep_as_is_makes_the_copy_count(world: Any) -> None:
    assert world["confirm"].status_code == 200, world["confirm"].text
    assert world["confirm"].json()["confirmed"] == ["history"]
    stage_read = world["after_confirm"].json()
    assert stage_read["provenance"]["singleton"]["history"]["source"] == "designer"
    assert "History of the craft" not in stage_read["completeness"]["missing"]


@needs_db
def test_a_resent_mark_does_not_undo_keep_as_is(world: Any) -> None:
    assert world["resent"].status_code == 200, world["resent"].text
    stage_read = world["after_resend"].json()
    assert stage_read["provenance"]["singleton"]["history"]["source"] == "designer"
    assert "History of the craft" not in stage_read["completeness"]["missing"]


@needs_db
def test_keep_as_is_is_refused_on_a_frozen_report(world: Any) -> None:
    assert world["confirm_frozen"].status_code == 403, world["confirm_frozen"].text


@needs_db
def test_somebody_not_on_the_workshop_is_offered_nothing(world: Any) -> None:
    assert world["other_sources"].status_code in (403, 404)
