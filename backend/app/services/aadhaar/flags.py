from __future__ import annotations

from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings

COLUMN_PREFIX = "aadhaarVerif"


def offline_enabled() -> bool:
    return bool(get_settings().aadhaar_offline_verification_enabled)


def online_enabled() -> bool:
    return bool(get_settings().aadhaar_online_auth_enabled)


def any_enabled() -> bool:
    return offline_enabled() or online_enabled()


def features() -> dict[str, Any]:
    settings = get_settings()
    online = bool(settings.aadhaar_online_auth_enabled)
    payload: dict[str, Any] = {
        "aadhaarOfflineVerification": bool(settings.aadhaar_offline_verification_enabled),
        "aadhaarOnlineAuth": online,
    }
    if online:
        payload["aadhaarOnlineEkyc"] = bool(settings.aadhaar_ekyc_enabled)
    return payload


def _not_found() -> HTTPException:
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")


def require_offline() -> None:
    if not offline_enabled():
        raise _not_found()


def require_online() -> None:
    if not online_enabled():
        raise _not_found()


def require_any() -> None:
    if not any_enabled():
        raise _not_found()


def hide_columns(payload: Any) -> Any:
    if any_enabled():
        return payload
    _strip(payload)
    return payload


def _strip(node: Any) -> None:
    if isinstance(node, list):
        for item in node:
            _strip(item)
        return
    if not isinstance(node, dict):
        return
    for key in [key for key in node if isinstance(key, str) and key.startswith(COLUMN_PREFIX)]:
        node.pop(key, None)
    items = node.get("items")
    if isinstance(items, list):
        _strip(items)
