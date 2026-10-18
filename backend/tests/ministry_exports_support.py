"""Shared seeding for the Ministry export pack tests (``test_ministry_exports_*.py``).

Not a test module. The pack tables are used by nothing else, so each test starts by clearing them
(:func:`reset`) — the ``(period, scheme)`` unique key would otherwise make one test's rows another's.
Storage is a dict (:class:`FakeBucket`); mail is switched on by passing a settings copy.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime
from typing import Any

from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password

DIGEST_TOPIC_PREFIX = "ministry-packs:"


def private(work: Any) -> Any:
    """Run ``work`` on its own connection, as ``test_notify_delivery_db.py`` does."""

    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


def own_client(work: Any) -> Any:
    """Run ``work(client)`` on a PRIVATE ``Prisma()``, for a test whose ``TestClient`` is alive.

    The shared ``db`` belongs to the app while a client runs, so connecting it here would be
    ``AlreadyConnectedError``; a client of our own on a loop of our own touches nothing the app holds.
    """
    from prisma import Prisma

    async def connected() -> Any:
        client = Prisma()
        await client.connect()
        try:
            return await work(client)
        finally:
            await client.disconnect()

    return asyncio.run(connected())


def settings_on(**extra: Any) -> Any:
    update = {
        "ministry_exports_enabled": True,
        "mail_from_address": "no-reply@dpw.example.org",
        **extra,
    }
    return get_settings().model_copy(update=update)


async def reset() -> None:
    await db.ministryexportdownload.delete_many()
    await db.ministryexportpack.delete_many()
    await db.ministryexportrecipient.delete_many()
    await db.emailmessage.delete_many(where={"topic": {"startswith": DIGEST_TOPIC_PREFIX}})


async def make_user(role: str, slug: str = "", email: str | None = None) -> Any:
    tag = uuid.uuid4().hex[:8]
    return await db.user.create(
        data={
            "email": email if email is not None else f"mx-{slug or role.lower()}-{tag}@example.org",
            "name": f"{(slug or role).title()} {tag}",
            "role": role,
            "passwordHash": hash_password("unused"),
        }
    )


async def make_workshop(owner_id: str, kind: str | None, **extra: Any) -> Any:
    tag = uuid.uuid4().hex[:6]
    data = {"title": f"Pack workshop {tag}", "createdById": owner_id, "workshopKind": kind, **extra}
    if data.get("status") == "APPROVED":
        data.setdefault("approvedAt", datetime.now(UTC))  # DesignWorkshop_approved_has_approvedAt
    return await db.designworkshop.create(data=data)


def bearer(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


class FakeBucket:
    """``s3.put_object_bytes`` / ``head_object`` / ``object_exists`` / ``presign_get_url`` over a dict."""

    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}
        self.fail_puts = 0
        self.presigned: list[dict[str, Any]] = []
        self.unreachable = False

    def put(self, key: str, data: bytes, *, mime_type: str) -> None:
        if self.fail_puts:
            self.fail_puts -= 1
            raise RuntimeError("bucket refused the upload")
        self.objects[key] = data

    def head(self, key: str) -> Any:
        if key not in self.objects:
            return None
        return type("Head", (), {"size_bytes": len(self.objects[key])})()

    def exists(self, key: str) -> bool | None:
        if self.unreachable:
            return None
        return key in self.objects

    def presign(
        self,
        key: str,
        *,
        filename: str,
        mime_type: str,
        expires_in: int = 900,
        disposition: str = "attachment",
    ) -> str:
        self.presigned.append({"key": key, "filename": filename, "expires_in": expires_in})
        return f"https://bucket.example/{key}?X-Amz-Signature=test"

    def install(self, monkeypatch: Any) -> FakeBucket:
        from app.services import s3

        monkeypatch.setattr(s3, "put_object_bytes", self.put)
        monkeypatch.setattr(s3, "head_object", self.head)
        monkeypatch.setattr(s3, "object_exists", self.exists)
        monkeypatch.setattr(s3, "presign_get_url", self.presign)
        return self
