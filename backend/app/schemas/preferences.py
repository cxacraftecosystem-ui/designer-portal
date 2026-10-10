from typing import Literal

from app.schemas.common import APIModel


class PreferencesUpdateRequest(APIModel):
    """A user's own appearance + accessibility preferences, sent whole on every save.

    Every field defaults, so a client that only knows about some of them still round-trips: the
    omitted ones fall back to "off" / "follow the system" rather than being rejected."""

    theme: Literal["system", "light", "dark"] = "system"
    reducedMotion: bool = False
    largerText: bool = False
    highContrast: bool = False


class NotificationPreferencesUpdate(APIModel):
    """A user's own e-mail opt-outs. Separate from the appearance body above, which every client
    sends whole on each save: a client that knows nothing about e-mail must not be able to switch a
    person's e-mails back on by saving a theme."""

    emailReviewNotes: bool
