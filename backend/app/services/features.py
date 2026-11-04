from __future__ import annotations

from typing import Any

from app.core.config import get_settings
from app.services.aadhaar import flags as aadhaar_flags


def features() -> dict[str, Any]:
    payload = aadhaar_flags.features()
    payload["ministryExports"] = bool(get_settings().ministry_exports_enabled)
    return payload
