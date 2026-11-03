from __future__ import annotations

import hashlib
import hmac
from datetime import UTC, datetime, timedelta

from app.core.db import db
from app.services.aadhaar.identity import AadhaarError


def uid_hash(uid: str, salt: bytes) -> str:
    return hmac.new(salt, uid.encode("ascii"), hashlib.sha256).hexdigest()


async def admit(
    user_id: str, hashed_uid: str, *, per_user: int, per_uid: int, window_minutes: int
) -> None:
    now = datetime.now(UTC)
    since = now - timedelta(minutes=window_minutes)
    await db.aadhaarotpthrottle.delete_many(where={"createdAt": {"lt": since}})
    by_user = await db.aadhaarotpthrottle.count(
        where={"userId": user_id, "createdAt": {"gte": since}}
    )
    if by_user >= per_user:
        raise AadhaarError(
            "rate_limited_user",
            f"You have asked for {per_user} codes in the last {window_minutes} minutes. Wait a "
            "while before asking again.",
            status_code=429,
        )
    by_uid = await db.aadhaarotpthrottle.count(
        where={"uidHash": hashed_uid, "createdAt": {"gte": since}}
    )
    if by_uid >= per_uid:
        raise AadhaarError(
            "rate_limited_uid",
            "Too many codes have been asked for this Aadhaar recently. Wait a while before asking "
            "again.",
            status_code=429,
        )
    await db.aadhaarotpthrottle.create(data={"userId": user_id, "uidHash": hashed_uid})
