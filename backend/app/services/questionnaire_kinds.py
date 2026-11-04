from __future__ import annotations

from app.questionnaire import kinds as _shared_kinds

KIND_LABELS: dict[str, str] = dict(_shared_kinds.KIND_LABELS)

KIND_TOKENS: frozenset[str] = frozenset(KIND_LABELS)

KIND_STAGE_KEYS: dict[str, str] = dict(_shared_kinds.STAGE_KEYS["designer-portal"])

NOT_STATED_LABEL = "Kind not stated"


def is_kind(value: str | None) -> bool:
    return bool(value) and value in KIND_TOKENS


def label_for(value: str | None) -> str:
    if not value:
        return NOT_STATED_LABEL
    return KIND_LABELS.get(value, value)


def stage_key_for(value: str | None) -> str | None:
    if not value:
        return None
    return KIND_STAGE_KEYS.get(value)


def coerce_kind(value: str | None) -> str | None:
    if value is None:
        return None
    token = value.strip().upper()
    if not token:
        return None
    if token not in KIND_TOKENS:
        allowed = ", ".join(sorted(KIND_TOKENS))
        raise ValueError(f"'{value}' is not a questionnaire kind. Use one of: {allowed}.")
    return token
