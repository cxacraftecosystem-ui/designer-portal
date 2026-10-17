"""The two sign-in times an account carries, written at the one point both credentials join.

``firstLoginAt`` is "the first time this account got in": written once and never moved, so "has this
person ever actually used the product" stays answerable. ``lastLoginAt`` (2026-10-17) moves on every
sign-in, so "last signed in" on the account screens is a fact rather than a guess.

ONE WRITE PER SIGN-IN, both stamps in it on the first. ``User`` has no ``updatedAt`` column, so this
write moves nothing a screen reads as "profile edited". The cached identity is dropped afterwards so
the next request reads the new times rather than the ones it had before the sign-in.

Kept out of ``routes/auth.py`` so the sign-in route only calls it: the route is owned by the account
work, and this is a fact about the account the rosters and the users screen read.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.core.deps import invalidate_cached_user


def sign_in_stamps(user: Any, now: datetime) -> dict[str, datetime]:
    """The columns one sign-in writes: always the last time, and the first time when there is none."""
    stamps: dict[str, datetime] = {"lastLoginAt": now}
    if getattr(user, "firstLoginAt", None) is None:
        stamps["firstLoginAt"] = now
    return stamps


async def stamp_sign_in(user: Any) -> None:
    """Record that ``user`` has just signed in. See the module docstring."""
    await db.user.update(where={"id": user.id}, data=sign_in_stamps(user, datetime.now(UTC)))
    invalidate_cached_user(user.id)
