from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from typing import Any

from app.schemas.design_workshops import DESIGN_WORKSHOP_STATUSES, WORKSHOP_KINDS
from app.services.records import enum_filter_or_422, plain

IST = timezone(timedelta(hours=5, minutes=30))


def _start_of(day: date) -> datetime:
    return datetime.combine(day, time.min, tzinfo=IST)


def apply_reader_list_filters(
    where: dict[str, Any],
    *,
    status: str | None = None,
    round_: int | None = None,
    state: str | None = None,
    workshop_kind: str | None = None,
    date_from: date | None = None,
    date_to: date | None = None,
) -> dict[str, Any]:
    if status and status.strip():
        where["status"] = enum_filter_or_422(status.strip(), DESIGN_WORKSHOP_STATUSES)
    if workshop_kind and workshop_kind.strip():
        where["workshopKind"] = enum_filter_or_422(
            workshop_kind.strip(), WORKSHOP_KINDS, field="workshopKind"
        )
    if round_ is not None:
        where["submissionRound"] = round_
    if state and state.strip():
        where["state"] = plain(state.strip())
    if date_from is not None and date_to is not None and date_from > date_to:
        date_from, date_to = date_to, date_from
    window: dict[str, Any] = {}
    if date_from is not None:
        window["gte"] = _start_of(date_from)
    if date_to is not None:
        window["lt"] = _start_of(date_to + timedelta(days=1))
    if window:
        where["startDate"] = window
    return where
