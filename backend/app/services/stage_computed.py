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

STAGES_WITH_COMPUTED = frozenset({ROSTER_STAGE, SURVEY_STAGE, CLOSING_STAGE})

FEMALE_TOKENS = frozenset({"female", "f", "woman", "women", "girl", "महिला", "स्त्री"})

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
    numbers = sorted(
        {n for row in schedule if (n := _int(row.get("dayNumber"))) and 0 < n <= MAX_WORKSHOP_DAYS}
    )
    if numbers:
        return numbers
    duration = setup_duration(setup)
    return list(range(1, duration + 1)) if duration else []


def _blank(value: Any) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def is_legacy_attendance(participant: Mapping[str, Any]) -> bool:
    return not isinstance(participant.get("absentDays"), list) and not _blank(
        participant.get("attendedDays")
    )


def absent_days(participant: Mapping[str, Any]) -> set[str]:
    raw = participant.get("absentDays")
    if not isinstance(raw, list):
        return set()
    return {str(_int(v)) for v in raw if _int(v) is not None}


def attended_days(participant: Mapping[str, Any], days: Sequence[int]) -> int | None:
    if not days or is_legacy_attendance(participant):
        return None
    absent = absent_days(participant)
    return sum(1 for day in days if str(day) not in absent)


def _named(rows: Iterable[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    return [r for r in rows if str(r.get("name") or "").strip() or r.get("artisanRef")]


def roster_values(
    participants: Sequence[Mapping[str, Any]], days: Sequence[int]
) -> dict[str, int | None]:
    people = _named(participants)
    if not people:
        return {"ROSTER_COUNT": None, "ROSTER_WOMEN": None, "ROSTER_COMPLETED": None}
    completed = (
        sum(1 for p in people if attended_days(p, days) == len(days))
        if days and not any(is_legacy_attendance(p) for p in people)
        else None
    )
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
    if not ref:
        return None
    wanted = str(ref)
    return next((p for p in participants if wanted in _row_keys(p)), None)


def refresh_counts(
    singletons: dict[str, dict[str, Any]],
    collections: Mapping[str, Mapping[str, list[dict[str, Any]]]],
    *,
    sittings: int | None = None,
    client_keys: Mapping[str, str] | None = None,
) -> None:
    keys = client_keys or {}

    def keyed(row: dict[str, Any]) -> dict[str, Any]:
        found = keys.get(str(row.get("_entryId") or ""))
        return {**row, "_clientKey": found} if found and not row.get("_clientKey") else row

    roster = collections.get(ROSTER_STAGE) or {}
    participants = roster.get(PARTICIPANT) or []
    days = workshop_days(roster.get(SCHEDULE_DAY) or [], singletons.get(SETUP_STAGE))
    for person in participants:
        _set(person, "attendedDays", attended_days(person, days))
    values = roster_values(participants, days)
    plan = singletons.get(ROSTER_STAGE)
    if plan is not None and values["ROSTER_COUNT"] is not None:
        _set(plan, "totalArtisans", values["ROSTER_COUNT"])
        _set(plan, "womenParticipants", values["ROSTER_WOMEN"])

    closing = singletons.get(CLOSING_STAGE)
    if closing is not None:
        _set(closing, "participantsCompleted", values["ROSTER_COMPLETED"])
    people = [keyed(p) for p in participants]
    for certificate in (collections.get(CLOSING_STAGE) or {}).get(CERTIFICATE) or []:
        person = participant_for(certificate.get("participantRef"), people)
        if person is not None:
            _set(certificate, "daysAttended", attended_days(person, days))

    summary = singletons.get(SURVEY_STAGE)
    if sittings is not None and summary is not None:
        responses = (collections.get(SURVEY_STAGE) or {}).get(SURVEY_RESPONSE) or []
        _set(summary, "responsesCollected", survey_responses(responses, sittings))



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
    from app.core.db import db

    forms = await db.questionnaire.find_many(
        where={
            "designWorkshopId": workshop_id,
            "isActive": True,
            "kind": "MARKET_SURVEY",
            "deletedAt": None,
        }
    )
    from app.services import questionnaire_readers

    bound = await questionnaire_readers.forms_for_design_workshop(workshop_id, kind="MARKET_SURVEY")
    form_ids = sorted({f.id for f in forms} | {str(b["id"]) for b in bound})
    if not form_ids:
        return 0
    sittings = await db.questionnaireformentry.count(
        where={"questionnaireId": {"in": form_ids}, "deletedAt": None}
    ) if forms else 0
    sessions = await questionnaire_readers.session_counts(form_ids)
    return sittings + sum(s["count"] for s in sessions.values())


async def apply_on_save(
    workshop_id: str,
    stage_key: str,
    pending: list[Any],
    survivors: Mapping[str, list[dict[str, Any]]],
    singleton_for: Any,
) -> None:
    if stage_key == ROSTER_STAGE:
        participants = survivors.get(PARTICIPANT, [])
        setup_rows = await _stage_rows(workshop_id, SETUP_STAGE)
        setup = next(
            (dict(r.data or {}) for r in setup_rows if r.entityKey == "workshopSetup"), None
        )
        days = workshop_days(survivors.get(SCHEDULE_DAY, []), setup)
        for item in pending:
            if item.entity.key == PARTICIPANT:
                value = attended_days(item.data, days)
                if value is not None:
                    _set(item.data, "attendedDays", value)
                    if not isinstance(item.data.get("absentDays"), list):
                        item.data["absentDays"] = []
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
