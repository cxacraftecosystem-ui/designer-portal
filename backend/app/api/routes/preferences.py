from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import can_approve_design_workshops, get_current_user, role_value
from app.schemas.preferences import NotificationPreferencesUpdate, PreferencesUpdateRequest
from app.services import email_outbox, mailer
from app.services.design_workshop_inspectors import holds_any_inspection

router = APIRouter(prefix="/preferences", tags=["preferences"])


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
    preferences = await db.userpreference.find_unique(where={"userId": current_user.id})
    return _serialize(preferences) if preferences else {}


@router.put("/me")
async def upsert_my_preferences(
    payload: PreferencesUpdateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    fields = {field: getattr(payload, field) for field in PREFERENCE_FIELDS}
    preferences = await db.userpreference.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return _serialize(preferences)


NOTIFICATION_DEFAULTS: dict[str, Any] = {
    "emailReviewNotes": True,
    "emailWorkshopUpdates": True,
    "emailAccessNotices": True,
    "emailHandInNotices": "EACH",
}


async def _reviews_hand_ins(user: Any) -> bool:
    if can_approve_design_workshops(user):
        return True
    if role_value(user) == "INSPECTOR":
        return True
    return await holds_any_inspection(user.id)


async def _notification_payload(row: Any, user: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {"available": mailer.mail_configured()}
    for field, default in NOTIFICATION_DEFAULTS.items():
        value = getattr(row, field, None) if row is not None else None
        payload[field] = default if value is None else value
    payload["emailHandInNotices"] = email_outbox.hand_in_mode(row)
    payload["reviewsHandIns"] = await _reviews_hand_ins(user)
    return payload


@router.get("/notifications")
async def my_notification_preferences(
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    row = await db.userpreference.find_unique(where={"userId": current_user.id})
    return await _notification_payload(row, current_user)


@router.put("/notifications")
async def update_my_notification_preferences(
    payload: NotificationPreferencesUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    fields = {
        field: getattr(payload, field)
        for field in NOTIFICATION_DEFAULTS
        if getattr(payload, field, None) is not None
    }
    if not fields:
        row = await db.userpreference.find_unique(where={"userId": current_user.id})
        return await _notification_payload(row, current_user)
    row = await db.userpreference.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return await _notification_payload(row, current_user)
