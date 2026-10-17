"""Stage capture that fills itself in: numbering, computed counts and one-tap suggestions.

Owner decisions of 2026-10-17 — A14, A16, B10, C6, C7, C8, C10, C12 — pinned here. The pure half
needs no database; the save path and the assist route at the bottom run against a local one and
skip themselves without it, exactly as ``test_stage_sync`` does.
"""

from __future__ import annotations

import os
import uuid
from datetime import date

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services import stage_assist as sa, stage_computed as sc
from app.services.stage_schema import (
    AUTO_NUMBER_SEQUENCE,
    Tier,
    field_to_dict,
    next_auto_number,
    registry_to_dict,
    stage,
    validate_registry,
)


def _field(stage_key: str, entity_key: str, field_key: str):
    entity = stage(stage_key).entity(entity_key)
    assert entity is not None, f"{stage_key} has no {entity_key}"
    spec = entity.field(field_key)
    assert spec is not None, f"{entity_key}.{field_key} is missing"
    return entity, spec


# --------------------------------------------------------------------------------------
# The registry declarations
# --------------------------------------------------------------------------------------


def test_the_registry_with_its_new_declarations_is_sound():
    assert validate_registry() == []


def test_each_row_type_numbers_itself_as_the_owner_decided():
    """C6: "S-01", "P-01", "D-01" per row type; iterations per prototype; days 1, 2, 3."""
    declared = {
        f"{entity.key}.{spec.key}": (spec.auto_number, spec.auto_number_within)
        for s in (stage(k) for k in ("SKETCH_DEVELOPMENT", "PROTOTYPE_DEVELOPMENT"))
        for entity in s.entities
        for spec in entity.fields
        if spec.auto_number
    }
    assert declared == {"sketch.sketchNo": ("S", ""), "prototype.prototypeCode": ("P", "")}
    assert (
        _field("FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct", "productCode")[1].auto_number == "D"
    )
    _, version = _field("PROTOTYPE_ITERATION", "prototypeIteration", "versionNo")
    assert (version.auto_number, version.auto_number_within) == (
        AUTO_NUMBER_SEQUENCE,
        "prototypeRef",
    )
    _, day = _field(sc.ROSTER_STAGE, sc.SCHEDULE_DAY, "dayNumber")
    _, when = _field(sc.ROSTER_STAGE, sc.SCHEDULE_DAY, "date")
    assert day.auto_number == AUTO_NUMBER_SEQUENCE and when.auto_date_from_day == "dayNumber"


def test_the_counts_replace_the_typed_boxes():
    """C7 and C8: every count named by the owner is computed, and is an optional INT."""
    computed = {
        f"{entity.key}.{spec.key}": spec.computed_kind
        for s in (stage(k) for k in (sc.ROSTER_STAGE, sc.SURVEY_STAGE, sc.CLOSING_STAGE))
        for entity in s.entities
        for spec in entity.fields
        if spec.computed_kind
    }
    assert computed == {
        "workshopPlan.totalArtisans": "ROSTER_COUNT",
        "workshopPlan.womenParticipants": "ROSTER_WOMEN",
        "participant.attendedDays": "ATTENDANCE_DAYS",
        "surveySummary.responsesCollected": "SURVEY_RESPONSES",
        "closing.participantsCompleted": "ROSTER_COMPLETED",
        "certificate.daysAttended": "ATTENDANCE_DAYS",
    }


def test_attendance_is_a_hidden_tick_list_and_not_a_box():
    _, absent = _field(sc.ROSTER_STAGE, sc.PARTICIPANT, "absentDays")
    assert absent.widget == "ATTENDANCE"
    assert not absent.required
    published = field_to_dict(absent, sc.PARTICIPANT)
    assert published["widget"] == "ATTENDANCE" and published["reportRole"] == "HIDDEN"


def test_the_venue_location_is_a_standard_field():
    """C12: the artisan-list import refuses a workshop without it, so it cannot hide in Advanced."""
    _, venue = _field(sc.SETUP_STAGE, "workshopSetup", "venueLocation")
    assert venue.tier is Tier.STANDARD


def test_planned_follow_ups_hold_dates_and_never_an_outcome():
    """B10: a planned visit cannot carry an adoption status, so it can never count in the figures."""
    entity = next(e for e in stage("POST_WORKSHOP_FOLLOWUP").entities if e.key == "plannedFollowUp")
    assert [f.key for f in entity.fields] == ["productRef", "interval", "dueDate"]
    assert all(f.report_role.value == "HIDDEN" and not f.required for f in entity.fields)


def test_the_clients_are_told_every_behaviour():
    published = registry_to_dict()
    fields = {
        f"{e['key']}.{f['key']}": f
        for s in published["stages"]
        for e in s["entities"]
        for f in e["fields"]
    }
    assert fields["sketch.sketchNo"]["autoNumber"] == "S"
    assert fields["prototypeIteration.versionNo"]["autoNumberWithin"] == "prototypeRef"
    assert fields["workshopScheduleDay.date"]["autoDateFromDay"] == "dayNumber"
    assert fields["workshopPlan.totalArtisans"]["computedKind"] == "ROSTER_COUNT"
    assert fields["prototype.sketchRef"]["refSuggest"] == {
        "fill": {"name": "name", "materials": "materials"},
        "hint": {"lengthCm": "lengthCm", "widthCm": "widthCm", "heightCm": "heightCm"},
    }
    # C10: the maker crosses BY REFERENCE — the roster row's id, not a name.
    assert fields["finalProduct.prototypeRef"]["refSuggest"]["fill"] == {"artisanRef": "artisanRef"}


# --------------------------------------------------------------------------------------
# Numbering
# --------------------------------------------------------------------------------------


def test_the_next_code_follows_the_highest_one_already_used():
    _, spec = _field("SKETCH_DEVELOPMENT", "sketch", "sketchNo")
    assert next_auto_number(spec, []) == "S-01"
    assert next_auto_number(spec, [{"sketchNo": "S-01"}, {"sketchNo": "s-07"}]) == "S-08"


def test_a_code_typed_another_way_never_collides_with_the_next():
    _, spec = _field("SKETCH_DEVELOPMENT", "sketch", "sketchNo")
    assert next_auto_number(spec, [{"sketchNo": "Sk1"}, {"sketchNo": "Runner"}]) == "S-03"


def test_iterations_number_within_their_own_prototype():
    _, spec = _field("PROTOTYPE_ITERATION", "prototypeIteration", "versionNo")
    rows = [
        {"prototypeRef": "p1", "versionNo": 1},
        {"prototypeRef": "p1", "versionNo": 2},
        {"prototypeRef": "p2", "versionNo": 1},
    ]
    assert next_auto_number(spec, rows, "p1") == 3
    assert next_auto_number(spec, rows, "p2") == 2
    assert next_auto_number(spec, rows, "p3") == 1


def test_a_field_that_numbers_nothing_answers_none():
    _, spec = _field("SKETCH_DEVELOPMENT", "sketch", "name")
    assert next_auto_number(spec, []) is None


# --------------------------------------------------------------------------------------
# The counts
# --------------------------------------------------------------------------------------

SETUP = {"startDate": "2026-10-05", "endDate": "2026-10-09"}


def test_workshop_days_come_from_the_schedule_then_from_the_dates():
    assert sc.workshop_days([{"dayNumber": 2}, {"dayNumber": 1}, {"dayNumber": 2}], SETUP) == [1, 2]
    assert sc.workshop_days([], SETUP) == [1, 2, 3, 4, 5]
    assert sc.workshop_days([], {"durationDays": 3}) == [1, 2, 3]
    assert sc.workshop_days([], None) == []


def test_an_untouched_roster_reads_all_present():
    people = [{"name": "Asha", "gender": "Female"}, {"name": "Ravi", "gender": "Male"}]
    values = sc.roster_values(people, [1, 2, 3])
    assert values == {"ROSTER_COUNT": 2, "ROSTER_WOMEN": 1, "ROSTER_COMPLETED": 2}
    assert sc.attended_days(people[0], [1, 2, 3]) == 3


def test_an_absence_lowers_days_attended_and_the_completion_count():
    people = [{"name": "Asha", "absentDays": ["2"]}, {"name": "Ravi"}, {"name": ""}]
    assert sc.attended_days(people[0], [1, 2, 3]) == 2
    assert sc.roster_values(people, [1, 2, 3])["ROSTER_COMPLETED"] == 1
    # The blank row a designer added and never filled is nobody.
    assert sc.roster_values(people, [1, 2, 3])["ROSTER_COUNT"] == 2


def test_nothing_to_count_never_overwrites_a_typed_legacy_value():
    assert sc.roster_values([], [1, 2])["ROSTER_COUNT"] is None
    assert sc.attended_days({"name": "Asha"}, []) is None
    assert sc.survey_responses([], 0) is None


def test_survey_responses_add_the_sittings_to_the_rows():
    """C8: counted and summarised, never duplicated as rows."""
    assert sc.survey_responses([{"response": "a"}, {"response": "b"}], 12) == 14


def test_a_certificate_finds_its_participant_by_either_key():
    people = [{"_entryId": "e1", "name": "Asha"}, {"_clientKey": "c2", "name": "Ravi"}]
    assert sc.participant_for("c2", people)["name"] == "Ravi"
    assert sc.participant_for("e1", people)["name"] == "Asha"
    assert sc.participant_for("nobody", people) is None


# --------------------------------------------------------------------------------------
# Suggestions
# --------------------------------------------------------------------------------------


def _workshop(singletons=None, collections=None, **kw) -> sa.Workshop:
    return sa.Workshop(singletons or {}, collections or {}, **kw)


def test_fill_the_schedule_adds_only_the_missing_days_dated_from_the_start():
    w = _workshop({"workshopSetup": SETUP}, {sc.SCHEDULE_DAY: [{"dayNumber": 1}]})
    [suggestion] = sa.schedule_suggestions(w)
    assert suggestion["kind"] == "ADD_ROWS" and suggestion["title"] == "Fill the schedule"
    assert suggestion["rows"][0] == {"dayNumber": 2, "date": "2026-10-06"}
    assert [r["dayNumber"] for r in suggestion["rows"]] == [2, 3, 4, 5]


def test_a_full_schedule_offers_nothing():
    rows = [{"dayNumber": n} for n in range(1, 6)]
    assert (
        sa.schedule_suggestions(_workshop({"workshopSetup": SETUP}, {sc.SCHEDULE_DAY: rows})) == []
    )


def test_a_prototype_is_offered_the_total_of_its_material_lines():
    """A16 on stage 13: "Use ₹X from the lines", and nothing changes without the tap."""
    proto = {"_entryId": "p1", "name": "Tote", "materialCost": "100.00"}
    lines = [
        {"prototypeRef": "p1", "material": "Jute", "amount": "120.00"},
        {"prototypeRef": "p1", "material": "Dye", "amount": "30.50"},
    ]
    [s] = sa.prototype_cost_suggestions(
        _workshop({}, {"prototype": [proto], "materialUsage": lines})
    )
    assert s["values"] == {"materialCost": "150.50"} and s["rowKey"] == "p1"
    assert s["title"] == "Use ₹150.50 from the lines"
    agreeing = dict(proto, materialCost="150.00")
    assert (
        sa.prototype_cost_suggestions(
            _workshop({}, {"prototype": [agreeing], "materialUsage": lines})
        )
        == []
    )


def test_an_unreadable_line_is_never_totalled_from_the_readable_half():
    proto = {"_entryId": "p1", "name": "Tote"}
    lines = [{"prototypeRef": "p1", "amount": "120.00"}, {"prototypeRef": "p1", "amount": "abc"}]
    assert (
        sa.prototype_cost_suggestions(_workshop({}, {"prototype": [proto], "materialUsage": lines}))
        == []
    )


def _costing() -> dict[str, list[dict]]:
    return {
        "prototype": [
            {"_entryId": "p1", "name": "Tote", "materialCost": "150", "labourCost": "400"}
        ],
        "materialUsage": [
            {
                "prototypeRef": "p1",
                "material": "Jute",
                "quantity": 2,
                "unit": "m",
                "rate": "60",
                "amount": "120.00",
            }
        ],
        "finalProduct": [{"_entryId": "f1", "name": "Tote bag", "prototypeRef": "p1"}],
        "costSheet": [{"_entryId": "c1", "productRef": "f1"}],
    }


def test_a_cost_sheet_is_offered_the_prototype_costs_and_its_material_lines():
    """C10: cost suggested from the prototype; optional "copy material lines"."""
    by_id = {s["id"]: s for s in sa.costing_suggestions(_workshop({}, _costing()))}
    costs = by_id["costSheet:fromPrototype:c1"]
    assert costs["values"] == {"materialCost": "150.00", "labourCost": "400.00"}
    lines = by_id["costSheet:materialLines:c1"]
    assert lines["entityKey"] == "costMaterialLine"
    assert lines["rows"] == [
        {
            "costSheetRef": "c1",
            "item": "Jute",
            "quantity": 2,
            "unit": "m",
            "rate": "60",
            "amount": "120.00",
        }
    ]


def test_a_sheet_that_already_has_lines_or_costs_is_not_offered_them_again():
    data = _costing()
    data["costSheet"][0].update(materialCost="10", labourCost="20")
    data["costMaterialLine"] = [{"costSheetRef": "c1", "item": "Cotton"}]
    assert sa.costing_suggestions(_workshop({}, data)) == []


def test_certificates_are_offered_for_everyone_present_every_day():
    people = [
        {"_entryId": "a", "name": "Asha"},
        {"_entryId": "b", "name": "Ravi", "absentDays": ["1"]},
        {"_entryId": "c", "name": "Mina"},
    ]
    w = _workshop(
        {"workshopSetup": SETUP},
        {sc.PARTICIPANT: people, sc.CERTIFICATE: [{"participantRef": "c"}]},
    )
    [s] = sa.certificate_suggestions(w)
    assert s["rows"] == [{"participantRef": "a", "daysAttended": 5}]


def test_stage_20_is_offered_the_workshops_own_template():
    """A14."""
    [s] = sa.report_suggestions(_workshop(template_id="PHOTO_CATALOGUE"))
    assert s["values"] == {"templateId": "PHOTO_CATALOGUE"} and s["rowKey"] is None
    chosen = _workshop(
        {"reportSettings": {"templateId": "DCH_STANDARD"}}, template_id="PHOTO_CATALOGUE"
    )
    assert sa.report_suggestions(chosen) == []
    assert sa.report_suggestions(_workshop(template_id="NOT_A_TEMPLATE")) == []


def test_stage_21_ticks_are_offered_with_the_evidence():
    """A14: "Saved 14 Oct 2026, PDF, 38 pages"; media only when every file is on the server."""
    w = _workshop(
        exports=[{"format": "pdf", "pageCount": 38, "generatedAt": "2026-10-14T09:00:00+00:00"}],
        media={"referenced": 12, "stored": 12},
    )
    by_id = {s["id"]: s for s in sa.archive_suggestions(w)}
    assert by_id["archive:reportSaved"]["detail"] == "Saved 14 Oct 2026, PDF, 38 pages."
    assert by_id["archive:mediaSaved"]["values"] == {"mediaSaved": True}
    pending = _workshop(media={"referenced": 12, "stored": 11})
    assert sa.archive_suggestions(pending) == []


def test_follow_ups_are_planned_as_dates_and_opened_when_due():
    """B10: plan stores due dates only; a due plan opens a follow-up with its outcome left blank."""
    setup = {"workshopSetup": {"endDate": "2026-01-31"}}
    products = [{"_entryId": "f1", "name": "Tote bag"}]
    to_plan, due = sa.follow_up_plan(
        _workshop(setup, {"finalProduct": products}, today=date(2026, 3, 1))
    )
    assert [(r["interval"], r["dueDate"]) for r in to_plan] == [
        ("M3", "2026-04-30"),
        ("M6", "2026-07-31"),
        ("M12", "2027-01-31"),
    ]
    assert due == []

    planned = [{"_entryId": "pl1", **r} for r in to_plan]
    w = _workshop(
        setup, {"finalProduct": products, "plannedFollowUp": planned}, today=date(2026, 5, 2)
    )
    suggestions = sa.follow_up_suggestions(w)
    [record] = [s for s in suggestions if s.get("due")]
    assert record["rows"] == [{"productRef": "f1", "interval": "M3", "followUpDate": "2026-04-30"}]
    assert "adoptionStatus" not in record["rows"][0]

    recorded = _workshop(
        setup,
        {
            "finalProduct": products,
            "plannedFollowUp": planned,
            "followUp": [{"productRef": "f1", "interval": "M3"}],
        },
        today=date(2026, 5, 2),
    )
    assert not [s for s in sa.follow_up_suggestions(recorded) if s.get("due")]


def test_add_months_keeps_to_the_end_of_a_short_month():
    assert sa.add_months(date(2026, 1, 31), 1) == date(2026, 2, 28)
    assert sa.add_months(date(2026, 11, 15), 3) == date(2027, 2, 15)


def test_the_assist_payload_carries_counts_and_days_for_display():
    people = [{"_entryId": "a", "name": "Asha", "gender": "F", "absentDays": ["2"]}]
    w = _workshop({"workshopSetup": SETUP}, {sc.PARTICIPANT: people})
    payload = sa.build_assist(w, sc.ROSTER_STAGE)
    assert payload["attendanceDays"] == [1, 2, 3, 4, 5]
    assert payload["startDate"] == "2026-10-05"
    assert payload["computed"]["singleton"] == {"totalArtisans": 1, "womenParticipants": 1}
    assert payload["computed"]["rows"]["participant"]["a"] == {"attendedDays": 4}
    survey = sa.build_assist(
        _workshop(
            {}, {sc.SURVEY_RESPONSE: [{"response": "x"}]}, sittings=[{"title": "Form", "count": 9}]
        ),
        sc.SURVEY_STAGE,
    )
    assert survey["computed"]["singleton"]["responsesCollected"] == 10
    assert survey["sittings"][0]["count"] == 9
    assert sa.build_assist(_workshop(), "CLUSTER_CRAFT_BACKGROUND")["suggestions"] == []


# --------------------------------------------------------------------------------------
# Against a database: the save stores the counts, the route answers
# --------------------------------------------------------------------------------------

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))
needs_db = pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def client():
    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import create_access_token, hash_password
    from app.main import app

    await db.connect()
    try:
        user = await db.user.create(
            data={
                "email": f"assist-{uuid.uuid4().hex[:8]}@example.org",
                "name": "Assist Test",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            }
        )
    finally:
        await db.disconnect()
    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield c


def _put(client, workshop_id: str, stage_key: str, entries: list[dict], replace: bool = True):
    response = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{stage_key}",
        json={"entries": entries, "replaceCollections": replace},
    )
    assert response.status_code == 200, response.text
    return response.json()


@needs_db
@pytest.mark.anyio
async def test_a_roster_save_stores_its_counts_over_whatever_was_typed(client):
    workshop = client.post("/api/design-workshops", json={"title": "Assist roster"}).json()["id"]
    _put(client, workshop, sc.SETUP_STAGE, [{"entityKey": "workshopSetup", "data": SETUP}])
    _put(
        client,
        workshop,
        sc.ROSTER_STAGE,
        [
            {"entityKey": "workshopPlan", "data": {"totalArtisans": 40}},
            {
                "entityKey": "participant",
                "ordinal": 0,
                "data": {
                    "_clientKey": "p-a",
                    "name": "Asha",
                    "gender": "Female",
                    "absentDays": ["3"],
                },
            },
            {
                "entityKey": "participant",
                "ordinal": 1,
                "data": {"_clientKey": "p-b", "name": "Ravi", "gender": "Male", "attendedDays": 1},
            },
        ],
    )
    stored = client.get(f"/api/design-workshops/{workshop}/stages/{sc.ROSTER_STAGE}").json()
    assert stored["singleton"]["totalArtisans"] == 2
    assert stored["singleton"]["womenParticipants"] == 1
    days = {p["name"]: p["attendedDays"] for p in stored["collections"]["participant"]}
    assert days == {"Asha": 4, "Ravi": 5}

    # A roster-only merge save still moves the singleton's counts.
    _put(
        client,
        workshop,
        sc.ROSTER_STAGE,
        [
            {
                "entityKey": "participant",
                "ordinal": 2,
                "merge": True,
                "data": {"_clientKey": "p-c", "name": "Mina", "gender": "F"},
            }
        ],
        replace=False,
    )
    stored = client.get(f"/api/design-workshops/{workshop}/stages/{sc.ROSTER_STAGE}").json()
    assert stored["singleton"]["totalArtisans"] == 3
    assert stored["singleton"]["womenParticipants"] == 2

    assist = client.get(f"/api/design-workshops/{workshop}/stages/{sc.ROSTER_STAGE}/assist").json()
    assert assist["attendanceDays"] == [1, 2, 3, 4, 5]
    assert assist["suggestions"][0]["title"] == "Fill the schedule"

    closing = _put(
        client,
        workshop,
        sc.CLOSING_STAGE,
        [{"entityKey": "closing", "data": {"participantsCompleted": 99}}],
    )
    assert closing
    stored = client.get(f"/api/design-workshops/{workshop}/stages/{sc.CLOSING_STAGE}").json()
    assert stored["singleton"]["participantsCompleted"] == 2


@needs_db
@pytest.mark.anyio
async def test_a_workshop_with_no_roster_keeps_its_typed_total(client):
    workshop = client.post("/api/design-workshops", json={"title": "Assist legacy"}).json()["id"]
    _put(
        client,
        workshop,
        sc.ROSTER_STAGE,
        [{"entityKey": "workshopPlan", "data": {"totalArtisans": 25}}],
    )
    stored = client.get(f"/api/design-workshops/{workshop}/stages/{sc.ROSTER_STAGE}").json()
    assert stored["singleton"]["totalArtisans"] == 25


@needs_db
@pytest.mark.anyio
async def test_the_assist_route_answers_every_stage_and_refuses_an_unknown_one(client):
    workshop = client.post("/api/design-workshops", json={"title": "Assist route"}).json()["id"]
    ok = client.get(f"/api/design-workshops/{workshop}/stages/REPORT_GENERATION/assist")
    assert ok.status_code == 200
    assert ok.json()["suggestions"][0]["values"] == {"templateId": "DCH_STANDARD"}
    assert client.get(f"/api/design-workshops/{workshop}/stages/NOPE/assist").status_code == 404


def test_planned_follow_ups_never_reach_the_report():
    """B10's due dates are the designer's own; the stage's one printable collection stays alone."""
    from app.services.report_builder import printable_collections

    keys = [e.key for e in printable_collections(stage("POST_WORKSHOP_FOLLOWUP"))]
    assert keys == ["followUp"]
    # Every other stage keeps every collection it had.
    for s in (stage(k) for k in ("PROTOTYPE_DEVELOPMENT", "COSTING_MARKET_LINKAGE")):
        assert printable_collections(s) == list(s.collections)
