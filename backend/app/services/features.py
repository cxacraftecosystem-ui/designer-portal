"""The optional-feature switches every client reads: ``GET /api/features`` and the ``features`` key
of the account payload (``auth.serialize_user``). One function so the two answers cannot differ.

Aadhaar's own switches come from ``services/aadhaar/flags.features`` unchanged; ``ministryExports``
(2026-10-18) tells the web client whether to draw the Ministry exports entry points.
"""

from __future__ import annotations

from typing import Any

from app.core.config import get_settings
from app.services.aadhaar import flags as aadhaar_flags


def features() -> dict[str, Any]:
    payload = aadhaar_flags.features()
    payload["ministryExports"] = bool(get_settings().ministry_exports_enabled)
    return payload
