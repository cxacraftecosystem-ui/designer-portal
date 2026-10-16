"""OTP rate limits: per caller and per Aadhaar number / VID, without storing the number.

UIDAI blocks an Aadhaar for 30 minutes after more than five unsubmitted OTPs in 30 minutes (error
953) and refuses a second OTP inside 30 seconds (952). This throttle sits in front of that, so one
account cannot exhaust a resident's allowance and a resident cannot be flooded with codes through
this server. The number is reduced to an HMAC under ``AADHAAR_THROTTLE_SALT`` before it touches the
database; the table (``AadhaarOtpThrottle``) holds that hash, the caller's id and a timestamp.
"""

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
    """Record one OTP request, or refuse it (429) when a limit is reached."""
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
