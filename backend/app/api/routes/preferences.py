from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.preferences import NotificationPreferencesUpdate, PreferencesUpdateRequest
from app.services import mailer

router = APIRouter(prefix="/preferences", tags=["preferences"])


# Every persisted preference echoed straight back to the client. Kept as one list so the
# serializer and the upsert stay in lockstep.
PREFERENCE_FIELDS = (
    "theme",
    "reducedMotion",
    "largerText",
    "highContrast",
)


def _serialize(row: Any) -> dict[str, Any]:
    data: dict[str, Any] = {
        "id": row.id,
        "userId": row.userId,
        "updatedAt": jsonable_encoder(row.updatedAt),
    }
    for field in PREFERENCE_FIELDS:
        data[field] = getattr(row, field, None)
    return data


@router.get("/me")
async def my_preferences(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The current user's saved preferences, or an empty object when they have never saved any.
    The client reads that emptiness as "this account has no opinion yet" and keeps the choices it
    already applied from localStorage, seeding the server with them."""
    preferences = await db.userpreference.find_unique(where={"userId": current_user.id})
    return _serialize(preferences) if preferences else {}


@router.put("/me")
async def upsert_my_preferences(
    payload: PreferencesUpdateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Create or update the current user's preferences — every signed-in user owns their own row."""
    fields = {field: getattr(payload, field) for field in PREFERENCE_FIELDS}
    preferences = await db.userpreference.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return _serialize(preferences)


@router.get("/notifications")
async def my_notification_preferences(
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Whether this deployment sends e-mail at all, and this person's opt-outs.

    ``available`` is what both clients read before drawing ANY e-mail control — the Settings
    switch, the "Send by e-mail" choice beside a password link. False means they draw none of it
    and say nothing about it. Readable by every signed-in account because it says nothing secret:
    only whether mail is on.
    """
    row = await db.userpreference.find_unique(where={"userId": current_user.id})
    return {
        "available": mailer.mail_configured(),
        "emailReviewNotes": bool(getattr(row, "emailReviewNotes", True)) if row else True,
    }


@router.put("/notifications")
async def update_my_notification_preferences(
    payload: NotificationPreferencesUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Save this person's e-mail opt-outs. Stored even while mail is off, so a choice made once
    still holds when mail is turned on."""
    fields = {"emailReviewNotes": payload.emailReviewNotes}
    row = await db.userpreference.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return {"available": mailer.mail_configured(), "emailReviewNotes": row.emailReviewNotes}
