"""Request bodies for ``app/api/routes/notifications.py`` beyond the list's own."""

from __future__ import annotations

from app.schemas.common import APIModel


class DigestPreferenceUpdate(APIModel):
    """``PUT /notifications/digest``: turn the Monday summary on or off."""

    enabled: bool
