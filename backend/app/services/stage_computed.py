"""Counts the server works out from other rows and stores on save (``FieldSpec.computed_kind``).

Owner decisions C7 and C8 (2026-10-17): attendance is ticked once a day and every count that
follows from it — days attended, the completion count, the certificates' days — is computed; the
roster's totals and women participants are counted from the roster; stage 8's "responses
collected" is its own rows plus the sittings of the workshop's market-survey questionnaires. The
typed boxes are REPLACED: both clients draw these read-only and the save overwrites them.

A count that cannot be formed — no roster rows, no workshop days — is ``None``, and ``None`` never
overwrites. That is what keeps a number typed on an older workshop readable.

The pure half (everything above :func:`apply_on_save`) is ported to ``frontend/lib/stageComputed.ts``
and Android's ``DwStageComputed.kt`` so the figure appears while the designer ticks.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from datetime import date
from typing import Any

SETUP_STAGE = "WORKSHOP_SETUP"
ROSTER_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
SURVEY_STAGE = "MARKET_SURVEY_CAPTURE"
CLOSING_STAGE = "INSPECTION_CLOSING"

PARTICIPANT = "participant"
SCHEDULE_DAY = "workshopScheduleDay"
SURVEY_RESPONSE = "surveyResponse"
CERTIFICATE = "certificate"

#: The stages whose save stores a computed count.
STAGES_WITH_COMPUTED = frozenset({ROSTER_STAGE, SURVEY_STAGE, CLOSING_STAGE})

#: How a roster's gender box reads as a woman. The box is free text carried from the artisan
#: record ("Female" there), so the common spellings and the Hindi word are accepted, casefolded.
FEMALE_TOKENS = frozenset({"female", "f", "woman", "women", "girl", "महिला", "स्त्री"})

#: Workshops longer than this are not ticked day by day; no real workshop runs this long.
MAX_WORKSHOP_DAYS = 366


def _int(value: Any) -> int | None:
    try:
        number = int(float(str(value).strip()))
    except TypeError, ValueError:
        return None
    return number


def is_woman(gender: Any) -> bool:
    return str(gender or "").strip().casefold() in FEMALE_TOKENS


def setup_duration(setup: Mapping[str, Any] | None) -> int | None:
    """Stage 1's duration: the stored box, else start to end inclusive."""
    if not setup:
        return None
    stored = _int(setup.get("durationDays"))
    if stored and stored > 0:
        return min(stored, MAX_WORKSHOP_DAYS)
    try:
        start = date.fromisoformat(str(setup.get("startDate") or "")[:10])
        end = date.fromisoformat(str(setup.get("endDate") or "")[:10])
    except ValueError:
        return None
    days = (end - start).days + 1
    return min(days, MAX_WORKSHOP_DAYS) if days > 0 else None


def workshop_days(
    schedule: Sequence[Mapping[str, Any]], setup: Mapping[str, Any] | None
) -> list[int]:
    """The day numbers attendance is taken on: the schedule's, else 1..duration, else none."""
    numbers = sorted(
        {n for row in schedule if (n := _int(row.get("dayNumber"))) and 0 < n <= MAX_WORKSHOP_DAYS}
    )
    if numbers:
        return numbers
    duration = setup_duration(setup)
    return list(range(1, duration + 1)) if duration else []


def absent_days(participant: Mapping[str, Any]) -> set[str]:
    raw = participant.get("absentDays")
    if not isinstance(raw, list):
        return set()
    return {str(_int(v)) for v in raw if _int(v) is not None}


def attended_days(participant: Mapping[str, Any], days: Sequence[int]) -> int | None:
    if not days:
        return None
    absent = absent_days(participant)
    return sum(1 for day in days if str(day) not in absent)


def _named(rows: Iterable[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    """Roster rows that are somebody: a name or a linked artisan. A blank added row is not."""
    return [r for r in rows if str(r.get("name") or "").strip() or r.get("artisanRef")]


def roster_values(
    participants: Sequence[Mapping[str, Any]], days: Sequence[int]
) -> dict[str, int | None]:
    """``ROSTER_COUNT``, ``ROSTER_WOMEN`` and ``ROSTER_COMPLETED`` for one roster."""
    people = _named(participants)
    if not people:
        return {"ROSTER_COUNT": None, "ROSTER_WOMEN": None, "ROSTER_COMPLETED": None}
    completed = sum(1 for p in people if attended_days(p, days) == len(days)) if days else None
    return {
        "ROSTER_COUNT": len(people),
        "ROSTER_WOMEN": sum(1 for p in people if is_woman(p.get("gender"))),
        "ROSTER_COMPLETED": completed,
    }


def survey_responses(rows: Sequence[Mapping[str, Any]], sittings: int) -> int | None:
    count = len(rows) + max(0, int(sittings or 0))
    return count or None


def _row_keys(row: Mapping[str, Any]) -> set[str]:
    return {str(v) for k in ("_entryId", "_clientKey", "id", "clientKey") if (v := row.get(k))}


def participant_for(
    ref: Any, participants: Sequence[Mapping[str, Any]]
) -> Mapping[str, Any] | None:
    """The roster row a REF names — by entry id or by client key, as every in-workshop REF is."""
    if not ref:
        return None
    wanted = str(ref)
    return next((p for p in participants if wanted in _row_keys(p)), None)


# --------------------------------------------------------------------------------------
# The save path
# --------------------------------------------------------------------------------------


def _set(data: dict[str, Any], key: str, value: int | None) -> bool:
    if value is None or data.get(key) == value:
        return False
    data[key] = value
    return True


async def _stage_rows(workshop_id: str, stage_key: str) -> list[Any]:
    from app.core.db import db

    return await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id, "stageKey": stage_key, "deletedAt": None},
        order={"ordinal": "asc"},
    )


def _with_keys(row: Any) -> dict[str, Any]:
    data = dict(row.data or {})
    data["_entryId"] = row.id
    if row.clientKey:
        data["_clientKey"] = row.clientKey
    return data


async def market_survey_sittings(workshop_id: str) -> int:
    """Sittings recorded against this workshop's active market-survey questionnaires."""
    from app.core.db import db

    forms = await db.questionnaire.find_many(
        where={"designWorkshopId": workshop_id, "isActive": True, "kind": "MARKET_SURVEY"}
    )
    if not forms:
        return 0
    return await db.questionnaireformentry.count(
        where={"questionnaireId": {"in": [f.id for f in forms]}}
    )


async def apply_on_save(
    workshop_id: str,
    stage_key: str,
    pending: list[Any],
    survivors: Mapping[str, list[dict[str, Any]]],
    singleton_for: Any,
) -> None:
    """Overwrite this save's computed fields with what the rows now come to.

    ``survivors`` maps a collection key to the rows it will hold after this save (sent rows plus
    untouched stored ones). ``singleton_for(entity_key)`` returns the PendingEntry for the stage's
    singleton, creating one over the stored row when this save did not send it, or ``None``.
    """
    if stage_key == ROSTER_STAGE:
        participants = survivors.get(PARTICIPANT, [])
        setup_rows = await _stage_rows(workshop_id, SETUP_STAGE)
        setup = next(
            (dict(r.data or {}) for r in setup_rows if r.entityKey == "workshopSetup"), None
        )
        days = workshop_days(survivors.get(SCHEDULE_DAY, []), setup)
        for item in pending:
            if item.entity.key == PARTICIPANT:
                _set(item.data, "attendedDays", attended_days(item.data, days))
        values = roster_values(participants, days)
        if values["ROSTER_COUNT"] is not None:
            plan = singleton_for("workshopPlan")
            if plan is not None:
                _set(plan.data, "totalArtisans", values["ROSTER_COUNT"])
                _set(plan.data, "womenParticipants", values["ROSTER_WOMEN"])
        return

    if stage_key == SURVEY_STAGE:
        count = survey_responses(
            survivors.get(SURVEY_RESPONSE, []), await market_survey_sittings(workshop_id)
        )
        summary = singleton_for("surveySummary") if count is not None else None
        if summary is not None:
            _set(summary.data, "responsesCollected", count)
        return

    if stage_key == CLOSING_STAGE:
        roster_rows, setup_rows = (
            await _stage_rows(workshop_id, ROSTER_STAGE),
            await _stage_rows(workshop_id, SETUP_STAGE),
        )
        participants = [_with_keys(r) for r in roster_rows if r.entityKey == PARTICIPANT]
        schedule = [_with_keys(r) for r in roster_rows if r.entityKey == SCHEDULE_DAY]
        setup = next(
            (dict(r.data or {}) for r in setup_rows if r.entityKey == "workshopSetup"), None
        )
        days = workshop_days(schedule, setup)
        for item in pending:
            if item.entity.key == CERTIFICATE:
                person = participant_for(item.data.get("participantRef"), participants)
                if person is not None:
                    _set(item.data, "daysAttended", attended_days(person, days))
        completed = roster_values(participants, days)["ROSTER_COMPLETED"]
        closing = singleton_for("closing") if completed is not None else None
        if closing is not None:
            _set(closing.data, "participantsCompleted", completed)
