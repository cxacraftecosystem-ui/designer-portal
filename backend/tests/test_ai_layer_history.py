"""AN AI LAYER'S DECISION HISTORY, AND ONE LAYER'S TEXT, READ ON THEIR OWN (F9, 2026-10-10).

Two reads, both on the workshop's own gate (anybody who can open the workshop):

* ``GET /api/design-workshops/{id}/ai-layers/decisions`` — who accepted, withdrew or declined which
  layer, and when, newest first, with the actor's name;
* ``GET /api/design-workshops/{id}/ai-layers/{layer_id}`` — one layer with its full text and its own
  history, the text WITHHELD exactly as the list withholds it when the recording underneath is one
  the caller may not read.

Asked of the workshop's designer, an admin, and a designer with no access to the workshop. The
history entries themselves are asserted without a database too (``ai_layers.history_entries``).
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import ai_layers

ROLES = {"designer": "DESIGNER", "admin": "ADMIN", "outsider": "DESIGNER", "uploader": "DESIGNER"}


def _auth(user_id: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user_id)}"}


# --------------------------------------------------------------------------------------
# The history, without a database
# --------------------------------------------------------------------------------------


class _Row:
    def __init__(self, **values: Any) -> None:
        self.__dict__.update(values)


def test_the_history_is_newest_first_and_carries_declines() -> None:
    at = datetime(2026, 10, 1, tzinfo=UTC)
    layers = [
        _Row(id="L1", kind="RAW_TRANSCRIPT", deletedAt=None, deletedById=None),
        _Row(id="L2", kind="SUMMARY", deletedAt=at + timedelta(hours=3), deletedById="u2"),
    ]
    decisions = [
        _Row(id="d1", layerId="L1", decision="ACCEPTED", note=None, actorId="u1", createdAt=at),
        _Row(
            id="d2",
            layerId="L1",
            decision="WITHDRAWN",
            note="wrong speaker",
            actorId="u1",
            createdAt=at + timedelta(hours=1),
        ),
    ]
    entries = ai_layers.history_entries(layers, decisions)
    assert [entry["decision"] for entry in entries] == ["DECLINED", "WITHDRAWN", "ACCEPTED"]
    declined = entries[0]
    assert declined["layerId"] == "L2"
    assert declined["layerKind"] == "SUMMARY"
    assert declined["actorId"] == "u2"
    assert entries[1]["note"] == "wrong speaker"
    assert entries[1]["layerKind"] == "RAW_TRANSCRIPT"
    assert all(entry["actorName"] is None for entry in entries)


def test_a_layer_never_decided_on_has_an_empty_history() -> None:
    layer = _Row(id="L1", kind="RAW_TRANSCRIPT", deletedAt=None, deletedById=None)
    assert ai_layers.history_entries([layer], []) == []


# --------------------------------------------------------------------------------------
# The two routes, against a database
# --------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    facts: dict[str, Any] = {"users": {}}

    async def seed() -> None:
        await db.connect()
        try:
            for key, role in ROLES.items():
                email = f"ai-history-{key}-{stamp}@example.org"
                user = await db.user.create(
                    data={
                        "email": email,
                        "name": f"History {key} {stamp}",
                        "role": role,
                        "passwordHash": hash_password("ai-history-test-password"),
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": email,
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": datetime.now(UTC),
                        "notes": "Seeded by tests/test_ai_layer_history.py.",
                    }
                )
                await db.designerroster.create(
                    data={"email": email, "isActive": True, "fullName": f"History {key}"}
                )
                facts["users"][key] = user.id
            users = facts["users"]
            workshop = await db.designworkshop.create(
                data={"title": f"AI history {stamp}", "createdById": users["designer"]}
            )
            facts["workshop"] = workshop.id

            async def media(owner: str, name: str, tagged: bool) -> str:
                extra = (
                    {"linkedRecordType": "designWorkshop", "linkedRecordId": workshop.id}
                    if tagged
                    else {}
                )
                row = await db.mediafile.create(
                    data={
                        "originalFilename": name,
                        "mediaType": "AUDIO",
                        "mimeType": "audio/mp4",
                        "sizeBytes": 4096,
                        "bucket": "test-bucket",
                        "objectKey": f"media/{owner}/{stamp}-{name}",
                        "uploadedById": owner,
                        **extra,
                    }
                )
                return row.id

            own = await media(users["designer"], "own.m4a", tagged=True)
            foreign = await media(users["uploader"], "foreign.m4a", tagged=False)

            async def layer(source: str, text: str) -> str:
                row = await db.dwailayer.create(
                    data={
                        "designWorkshopId": workshop.id,
                        "kind": "RAW_TRANSCRIPT",
                        "tier": "TIER_3",
                        "sourceMediaId": source,
                        "provider": "test",
                        "modelId": "test-model",
                        "text": text,
                        "createdById": users["designer"],
                    }
                )
                return row.id

            facts["readable"] = await layer(own, f"The master weaver speaks {stamp}.")
            facts["withheld"] = await layer(foreign, f"Somebody else's recording {stamp}.")
            facts["declined"] = await layer(own, f"A layer somebody said no to {stamp}.")
            at = datetime.now(UTC)
            await db.dwailayerdecision.create(
                data={
                    "layerId": facts["readable"],
                    "decision": "ACCEPTED",
                    "actorId": users["designer"],
                    "createdAt": at - timedelta(minutes=10),
                }
            )
            await db.dwailayerdecision.create(
                data={
                    "layerId": facts["readable"],
                    "decision": "WITHDRAWN",
                    "note": "Speaker labels are wrong",
                    "actorId": users["admin"],
                    "createdAt": at - timedelta(minutes=5),
                }
            )
            await db.dwailayer.update(
                where={"id": facts["declined"]},
                data={"deletedAt": at, "deletedById": users["designer"]},
            )
        finally:
            await db.disconnect()

    asyncio.run(seed())

    answers: dict[str, Any] = {}
    users = facts["users"]
    base = f"/api/design-workshops/{facts['workshop']}/ai-layers"
    with TestClient(app) as client:
        for actor in ("designer", "admin", "outsider"):
            answers[f"history-{actor}"] = client.get(f"{base}/decisions", headers=_auth(users[actor]))
            for key in ("readable", "withheld", "declined"):
                answers[f"{key}-{actor}"] = client.get(
                    f"{base}/{facts[key]}", headers=_auth(users[actor])
                )
        answers["unknown"] = client.get(f"{base}/not-a-layer", headers=_auth(users["designer"]))
    facts["answers"] = answers
    yield facts




@needs_db
def test_the_workshop_history_names_who_did_what_newest_first(world) -> None:
    reply = world["answers"]["history-designer"]
    assert reply.status_code == 200, reply.text
    items = reply.json()["items"]
    assert [item["decision"] for item in items] == ["DECLINED", "WITHDRAWN", "ACCEPTED"]
    assert items[0]["layerId"] == world["declined"]
    assert items[1]["note"] == "Speaker labels are wrong"
    assert items[1]["actorName"].startswith("History admin")
    assert items[2]["actorName"].startswith("History designer")
    assert all(item["layerKind"] == "RAW_TRANSCRIPT" for item in items)
    assert reply.json()["total"] == 3


@needs_db
def test_an_admin_reads_the_same_history(world) -> None:
    assert world["answers"]["history-admin"].json() == world["answers"]["history-designer"].json()


@needs_db
def test_a_designer_without_access_to_the_workshop_reads_nothing(world) -> None:
    answers = world["answers"]
    assert answers["history-outsider"].status_code == 404
    for key in ("readable", "withheld", "declined"):
        assert answers[f"{key}-outsider"].status_code == 404, key


@needs_db
def test_one_layer_comes_back_with_its_full_text_and_history(world) -> None:
    reply = world["answers"]["readable-designer"]
    assert reply.status_code == 200, reply.text
    body = reply.json()
    assert body["layer"]["text"].startswith("The master weaver speaks")
    assert body["layer"]["textWithheld"] is False
    assert [entry["decision"] for entry in body["decisions"]] == ["WITHDRAWN", "ACCEPTED"]


@needs_db
def test_one_layers_text_is_withheld_where_the_recording_is_not_the_callers(world) -> None:
    reply = world["answers"]["withheld-designer"]
    assert reply.status_code == 200, reply.text
    layer = reply.json()["layer"]
    assert layer["textWithheld"] is True
    assert not layer.get("text")
    assert not layer.get("preview")
    # An admin may read every recording, so the same layer is readable to them.
    admin = world["answers"]["withheld-admin"].json()["layer"]
    assert admin["textWithheld"] is False
    assert admin["text"].startswith("Somebody else's recording")


@needs_db
def test_a_declined_layer_is_still_readable_with_its_decline(world) -> None:
    reply = world["answers"]["declined-designer"]
    assert reply.status_code == 200, reply.text
    decisions = reply.json()["decisions"]
    assert [entry["decision"] for entry in decisions] == ["DECLINED"]


@needs_db
def test_an_id_that_is_not_this_workshops_is_a_404(world) -> None:
    assert world["answers"]["unknown"].status_code == 404
