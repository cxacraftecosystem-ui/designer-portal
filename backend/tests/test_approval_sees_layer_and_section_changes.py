from __future__ import annotations

import asyncio
import os
import uuid
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401
from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
    ),
]

ACCOUNTS = {"ministry": "MINISTRY_ADMIN", "admin": "ADMIN", "designer": "DESIGNER"}
WORKSHOPS = ("layer", "sections")


async def _seed() -> dict[str, Any]:
    from app.services import ai_layers

    tag = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        people: dict[str, Any] = {}
        for slug, role in ACCOUNTS.items():
            people[slug] = await db.user.create(
                data={
                    "email": f"stale-approval-{slug}-{tag}@example.org",
                    "name": f"{slug.title()} {tag}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                }
            )
        await db.designerroster.create(
            data={
                "email": f"stale-approval-designer-{tag}@example.org",
                "fullName": "Roster row",
                "institution": "Directorate of Handicrafts",
                "isActive": True,
                "addedById": people["admin"].id,
            }
        )
        workshops: dict[str, str] = {}
        for name in WORKSHOPS:
            row = await db.designworkshop.create(
                data={
                    "title": f"Stale approval {name} {tag}",
                    "createdById": people["admin"].id,
                    "status": "PRE_SUBMISSION",
                    "submissionRound": 1,
                }
            )
            workshops[name] = row.id
            await db.designworkshopviewer.create(
                data={
                    "designWorkshopId": row.id,
                    "userId": people["designer"].id,
                    "grantedById": people["admin"].id,
                }
            )
        layer = await ai_layers.apply_plan(
            ai_layers.layer_create_plan(
                workshop_id=workshops["layer"],
                kind=ai_layers.LayerKind.PROOFREAD,
                tier=ai_layers.AiTier.TIER_3,
                source=ai_layers.LayerSource.supplied_text("the dabu paist is mixt"),
                provider="test",
                model_id="dummy-model",
                text="The dabu paste is mixed.",
                created_by_id=people["designer"].id,
            )
        )
        return {"people": people, "workshops": workshops, "layer": layer.id}
    finally:
        await db.disconnect()


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    seeded = asyncio.run(_seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


def _as(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _opened_at(world: dict[str, Any], name: str) -> str:
    read = world["client"].get(
        f"/api/design-workshop-approvals/{world['workshops'][name]}", headers=_as(world, "ministry")
    )
    assert read.status_code == 200, read.text
    return read.json()["updatedAt"]


def _approve(world: dict[str, Any], name: str, read_at: str):
    return world["client"].post(
        f"/api/design-workshop-approvals/{world['workshops'][name]}/approve",
        json={"round": 1, "readAt": read_at, "note": "Satisfied."},
        headers=_as(world, "ministry"),
    )


def test_accepting_an_ai_layer_after_the_approver_opened_the_report_refuses_the_approval(world):
    read_at = _opened_at(world, "layer")
    accepted = world["client"].post(
        f"/api/design-workshops/{world['workshops']['layer']}/ai-layers/{world['layer']}/accept",
        json={},
        headers=_as(world, "designer"),
    )
    assert accepted.status_code == 200, accepted.text
    approved = _approve(world, "layer", read_at)
    assert approved.status_code == 409, approved.text
    assert "changed after you opened it" in approved.json()["detail"]


def test_changing_custom_sections_after_the_approver_opened_the_report_refuses_the_approval(world):
    read_at = _opened_at(world, "sections")
    saved = world["client"].put(
        f"/api/design-workshops/{world['workshops']['sections']}/custom-sections",
        json={
            "sections": [
                {
                    "key": "staleapproval",
                    "title": "Dye notes",
                    "stageKey": "CLUSTER_CRAFT_BACKGROUND",
                    "fields": [{"key": "dyenote", "label": "Dye note", "type": "TEXT"}],
                }
            ]
        },
        headers=_as(world, "designer"),
    )
    assert saved.status_code == 200, saved.text
    approved = _approve(world, "sections", read_at)
    assert approved.status_code == 409, approved.text
    assert "changed after you opened it" in approved.json()["detail"]
