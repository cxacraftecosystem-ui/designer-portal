"""The filters on a read-only workshop list: status, submission round, state, type and dates.

Sweep item F13 (2026-10-10): the inspection list had a search box and nothing else, so an inspector
with forty workshops could not ask for "the ones handed in for a second round in Odisha last month".
This builds those narrowings in ONE place, so a second read-only list can take the same set without a
second spelling of what each one means.

EVERY FILTER IS AND-COMPOSED INTO ``where``, AND NONE OF THEM IS A SCOPE. The caller appends its own
scope clause (the inspection surface's own) to ``where["AND"]`` as before; this function writes plain
column keys only and never touches ``where["OR"]``, which the search box owns. A filter can therefore
only remove rows the scope already admitted — there is no value of any parameter that adds one.

THE TREATMENTS ARE THE DESIGNER LIST'S (``GET /design-workshops``), for its reasons:

* ``status`` and ``workshopKind`` are closed vocabularies checked by ``enum_filter_or_422``, so a
  stale bookmark is a 422 naming the values rather than a 500 or an empty list that lies;
* ``state`` is a literal from the address list, through ``plain`` (a NUL byte is a driver error);
* ``round`` is ``DesignWorkshop.submissionRound`` — 0 for a report never handed in, and the round a
  correction is filed against after that;
* ``dateFrom`` / ``dateTo`` bound the day the workshop STARTED (``startDate``, promoted from stage 1),
  inclusive at both ends in India Standard Time — the day printed on the row.
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from typing import Any

from app.schemas.design_workshops import DESIGN_WORKSHOP_STATUSES, WORKSHOP_KINDS
from app.services.records import enum_filter_or_422, plain

#: The day a workshop "ran on" is the day as printed in India, where every workshop is held.
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
    """Write the requested narrowings into ``where`` and return it. Absent or blank means "any"."""
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
        # Asked backwards: answer the range the reader meant rather than an empty list.
        date_from, date_to = date_to, date_from
    window: dict[str, Any] = {}
    if date_from is not None:
        window["gte"] = _start_of(date_from)
    if date_to is not None:
        # Inclusive of the whole last day: everything before the start of the next one.
        window["lt"] = _start_of(date_to + timedelta(days=1))
    if window:
        where["startDate"] = window
    return where
