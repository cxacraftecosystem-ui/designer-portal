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
    "codes": "app.services.cross_portal_codes",
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
