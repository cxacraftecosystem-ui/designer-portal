from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.core.deps import invalidate_cached_user


def sign_in_stamps(user: Any, now: datetime) -> dict[str, datetime]:
    stamps: dict[str, datetime] = {"lastLoginAt": now}
    if getattr(user, "firstLoginAt", None) is None:
        stamps["firstLoginAt"] = now
    return stamps


async def stamp_sign_in(user: Any) -> None:
    await db.user.update(where={"id": user.id}, data=sign_in_stamps(user, datetime.now(UTC)))
    invalidate_cached_user(user.id)
