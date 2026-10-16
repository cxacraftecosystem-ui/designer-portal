"""The two Aadhaar switches, and what "off" means.

OFF MEANS ABSENT, NOT FORBIDDEN. Every Aadhaar route answers 404 — the answer a route that does not
exist gives — rather than 403, which would advertise a feature the caller is merely not allowed to
use. The result columns are withheld from every account and artisan payload, and both clients draw
nothing: they learn the state from ``features`` on the session payload (``auth.serialize_user``)
and from ``GET /api/features``, and an Android handset that has never been told defaults to hidden.

Read at REQUEST time from ``get_settings()`` rather than when the router is built, so a test (or an
operator restarting with a changed environment) sees the routes appear and disappear without the
module being reloaded.
"""

from __future__ import annotations

from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings

#: Every column the verification writes on User and Artisan carries this prefix, which is what lets
#: one test assert that nothing else was ever added and one helper withhold them all.
COLUMN_PREFIX = "aadhaarVerif"


def offline_enabled() -> bool:
    return bool(get_settings().aadhaar_offline_verification_enabled)


def online_enabled() -> bool:
    return bool(get_settings().aadhaar_online_auth_enabled)


def any_enabled() -> bool:
    return offline_enabled() or online_enabled()


def features() -> dict[str, Any]:
    """What a client may draw. The only Aadhaar-related keys any payload carries while both are off
    are these two booleans, both false."""
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
    """Withhold the result columns from a payload while both features are off.

    Covers a dict, a list of dicts, and a page payload (``{"items": [...]}``) — the three shapes the
    account and artisan routes return. Mutates and returns ``payload``. A no-op while either feature
    is on, so a verified badge drawn by a client always has its data.
    """
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
