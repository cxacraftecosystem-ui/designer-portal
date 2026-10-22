"""Designer-portal's side of the shared questionnaire package's ports (``app/questionnaire/ports.py``).

The package is byte-identical in both portals and never imports portal code; it reaches this module
by its well-known name. Every function looks its target up at call time, so a test that patches
``app.core.db.db`` or a service module is seen by the package.

Every port is served (U1, 22 Oct 2026):

* ``consent_gate`` is ``services/questionnaire_consent``: the shared ledger rule over a take's
  subjects plus the design workshop's dictation consent (opt-out; C4, DR audit H3);
* ``transcription`` is ``services/questionnaire_transcription``: the pause-aware enqueue, which
  writes a QUEUED job and never calls a provider;
* ``workshop_access`` is ``services/workshop_access`` (the Workshop submission rules, identical in
  shape to documentation-portal's). A session on a DESIGN workshop is checked in the route layer
  (``api/routes/questionnaire_v2.py``) with the design workshop's own filing rule.
"""

from __future__ import annotations

import importlib
from types import ModuleType
from typing import Any

PORTAL = "designer-portal"

_MODULES = {
    "naming": "app.services.naming",
    "s3": "app.services.s3",
    "records": "app.services.records",
    "answer_drafts": "app.services.answer_drafts",
    "consent_gate": "app.services.questionnaire_consent",
    "transcription": "app.services.questionnaire_transcription",
    "workshop_access": "app.services.workshop_access",
}


def db() -> Any:
    from app.core import db as core_db

    return core_db.db


def get_settings() -> Any:
    from app.core.config import get_settings as settings

    return settings()


def has_rank(user: Any, role: str) -> bool:
    from app.core import deps

    return deps.has_rank(user, role)


def is_admin(user: Any) -> bool:
    from app.core import deps

    return deps.is_admin(user)


def is_content_admin(user: Any) -> bool:
    from app.core import deps

    return deps.is_content_admin(user)


def can_review(user: Any) -> bool:
    from app.core import deps

    return deps.can_review(user)


def can_create_records(user: Any) -> bool:
    from app.core import deps

    return deps.can_create_records(user)


def can_manage_questionnaire(user: Any) -> bool:
    from app.core import deps

    return deps.can_manage_questionnaire(user)


def module(name: str) -> ModuleType:
    return importlib.import_module(_MODULES[name])
