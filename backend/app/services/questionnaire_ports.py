"""Designer-portal's side of the shared questionnaire package's ports (``app/questionnaire/ports.py``).

The package is byte-identical in both portals and never imports portal code; it reaches this module
by its well-known name. Every function looks its target up at call time, so a test that patches
``app.core.db.db`` or a service module is seen by the package.

U0 (22 Oct 2026) SHIPS THE PACKAGE DARK HERE: nothing mounts its routes until U1. The database, the
settings, the role ladder, naming, storage, AI drafting and ``attach_location`` are served now. The
consent gate, the transcription enqueue and the workshop-submission check are the three ports whose
designer-portal side U1 writes (the gate adds the design workshop's dictation consent; the enqueue
goes through this portal's transcription jobs; workshop access is a design workshop's membership),
and until then they refuse with :class:`PortUnavailable` rather than guess.
"""

from __future__ import annotations

import importlib
from types import ModuleType
from typing import Any

from app.questionnaire.ports import PortUnavailable

PORTAL = "designer-portal"

_MODULES = {
    "naming": "app.services.naming",
    "s3": "app.services.s3",
    "records": "app.services.records",
    "answer_drafts": "app.services.answer_drafts",
}
_LATER = {
    "consent_gate": "U1 wires designer-portal's consent gate (the ledger plus DesignWorkshop.dictationConsent).",
    "transcription": "U1 wires the enqueue through designer-portal's transcription jobs.",
    "workshop_access": "U1 wires the design-workshop submission check.",
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
    if name in _LATER:
        raise PortUnavailable(f"designer-portal does not serve the questionnaire port '{name}' yet: {_LATER[name]}")
    return importlib.import_module(_MODULES[name])
