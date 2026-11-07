from __future__ import annotations

import asyncio
import uuid
from typing import Any

from conftest import needs_db
from query_count_support import asgi_client

from app.core.db import db
from app.core.deps import invalidate_cached_user
from app.core.security import create_access_token, hash_password

pytestmark = [needs_db]

STAMP = uuid.uuid4().hex[:8]


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


def _auth(user_id: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user_id)}"}


async def _person(slug: str, role: str) -> Any:
    return await db.user.create(
        data={
            "email": f"task-demoted-{slug}-{STAMP}@example.org",
            "name": f"Task {slug} {STAMP}",
            "role": role,
            "passwordHash": hash_password("unused"),
        }
    )


def test_a_demoted_creator_no_longer_edits_cancels_or_reassigns_its_tasks() -> None:
    async def story() -> dict[str, Any]:
        author = await _person("author", "ADMIN")
        worker = await _person("worker", "RESEARCHER")
        helper = await _person("helper", "FIELD_CONTRIBUTOR")
        task = await db.assignedtask.create(
            data={
                "title": f"Demotion task {STAMP}",
                "recordTypes": ["product"],
                "assigneeId": worker.id,
                "createdById": author.id,
            }
        )
        async with asgi_client() as client:
            before = await client.patch(
                f"/api/tasks/{task.id}", json={"description": "still admin"}, headers=_auth(author.id)
            )
            await db.user.update(where={"id": author.id}, data={"role": "DESIGNER"})
            invalidate_cached_user(author.id)
            cancelled = await client.patch(
                f"/api/tasks/{task.id}",
                json={"status": "CANCELLED", "description": "changed"},
                headers=_auth(author.id),
            )
            reassigned = await client.patch(
                f"/api/tasks/{task.id}", json={"assigneeId": helper.id}, headers=_auth(author.id)
            )
            stored = await db.assignedtask.find_unique(where={"id": task.id})
        return {
            "before": before.status_code,
            "cancelled": cancelled.status_code,
            "reassigned": reassigned.status_code,
            "status": str(getattr(stored.status, "value", stored.status)),
            "assignee": stored.assigneeId == worker.id,
        }

    answer = _private(story)
    assert answer["before"] == 200
    assert answer["cancelled"] == 403
    assert answer["reassigned"] == 403
    assert answer["status"] != "CANCELLED"
    assert answer["assignee"] is True
