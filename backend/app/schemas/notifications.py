from __future__ import annotations

from app.schemas.common import APIModel


class DigestPreferenceUpdate(APIModel):
    enabled: bool
