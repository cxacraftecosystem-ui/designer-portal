from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import ValidationError

from app.api.routes import designers as designer_routes
from app.schemas.designers import DesignerProfileUpdate
from app.schemas.records import ArtisanCreate, ArtisanUpdate
from app.services import (
    designers as designer_service,
    identity as identity_service,
    records as record_service,
)
from app.services.designers import PROFILE_FIELDS, profile_payload, update_profile
from app.services.records import attach_location

BAGRU = {"latitude": 26.8137, "longitude": 75.545}
KHARAGPUR = {"latitude": 22.3149, "longitude": 87.3105}

USER_ID = "user-latha"



class _LocationTable:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, data: dict[str, Any]) -> SimpleNamespace:
        self.created.append(dict(data))
        row = SimpleNamespace(
            id=f"loc-{len(self.created)}",
            createdAt=datetime(2026, 8, 29, 9, 0, tzinfo=UTC),
            updatedAt=datetime(2026, 8, 29, 9, 0, tzinfo=UTC),
        )
        for key, value in data.items():
            setattr(row, key, value)
        return row


class _ProfileTable:
    def __init__(self, locations: _LocationTable) -> None:
        self.rows: dict[str, dict[str, Any]] = {}
        self.locations = locations
        self.calls: list[dict[str, Any]] = []

    async def upsert(
        self, where: dict[str, Any], data: dict[str, Any], include: dict[str, Any] | None = None
    ) -> SimpleNamespace:
        self.calls.append({"where": where, "data": data, "include": include})
        user_id = where["userId"]
        stored = self.rows.get(user_id)
        if stored is None:
            create = dict(data["create"])
            create.pop("user", None)
            stored = {"id": f"profile-{user_id}", "userId": user_id, **create}
            self.rows[user_id] = stored
        else:
            stored.update(data["update"])
        return self._row(stored, include)

    async def find_unique(
        self, where: dict[str, Any], include: dict[str, Any] | None = None
    ) -> SimpleNamespace | None:
        self.calls.append({"where": where, "include": include})
        if "userId" not in where:
            return None
        stored = self.rows.get(where["userId"])
        return None if stored is None else self._row(stored, include)

    def _row(self, stored: dict[str, Any], include: dict[str, Any] | None) -> SimpleNamespace:
        row = SimpleNamespace(
            id=stored["id"],
            userId=stored["userId"],
            locationId=stored.get("locationId"),
            createdAt=datetime(2026, 8, 20, 12, 0, tzinfo=UTC),
            updatedAt=datetime(2026, 8, 29, 9, 0, tzinfo=UTC),
        )
        for key in PROFILE_FIELDS:
            setattr(row, key, stored.get(key))
        if include and include.get("location"):
            row.location = self._location(stored.get("locationId"))
        if include and include.get("user"):
            row.user = SimpleNamespace(name="Latha Rao")
        return row

    def _location(self, location_id: str | None) -> SimpleNamespace | None:
        for index, data in enumerate(self.locations.created, start=1):
            if f"loc-{index}" != location_id:
                continue
            row = SimpleNamespace(id=location_id)
            for key, value in data.items():
                setattr(row, key, value)
            return row
        return None


class _Db:
    def __init__(self) -> None:
        self.location = _LocationTable()
        self.designerprofile = _ProfileTable(self.location)


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch) -> _Db:
    stub = _Db()
    monkeypatch.setattr(record_service, "db", stub)
    monkeypatch.setattr(designer_service, "db", stub)
    monkeypatch.setattr(identity_service, "db", stub)
    return stub


async def _save(body: dict[str, Any], *, user_id: str = USER_ID) -> dict[str, Any]:
    payload = DesignerProfileUpdate(**body)
    values = payload.model_dump(exclude_unset=True)
    values = await attach_location(values)
    return profile_payload(await update_profile(user_id, values))



async def test_a_stated_address_with_its_district_round_trips(db: _Db) -> None:
    saved = await _save(
        {
            "displayName": "Latha Nayak",
            "location": {**BAGRU, "state": "Rajasthan", "district": "Jaipur", "pincode": "303007"},
        }
    )

    assert saved["location"] is not None, "the profile came back with no address at all"
    assert saved["location"]["state"] == "Rajasthan"
    assert saved["location"]["district"] == "Jaipur"
    assert saved["location"]["pincode"] == "303007"
    assert saved["locationId"] == saved["location"]["id"], (
        "the id beside the object disagrees with the object, so a client cannot tell which to trust"
    )


async def test_the_saved_address_is_readable_again_on_the_next_read(db: _Db) -> None:
    await _save({"location": {**BAGRU, "state": "Rajasthan", "district": "Jaipur"}})

    again = profile_payload(await designer_service.get_or_create_profile(USER_ID))

    assert again["location"] is not None, (
        "GET answered no address for a profile that has one — PROFILE_INCLUDE is not being passed"
    )
    assert again["location"]["district"] == "Jaipur"
    assert all(call["include"] == {"location": True} for call in db.designerprofile.calls), (
        "some profile query does not load the relation: "
        f"{[call['include'] for call in db.designerprofile.calls]}"
    )


async def test_a_profile_write_invents_no_coordinate(db: _Db) -> None:
    saved = await _save({"displayName": "Latha Nayak", "city": "Bhubaneswar", "state": "Odisha"})

    assert db.location.created == [], (
        f"a profile save with no address created {len(db.location.created)} Location row(s): "
        f"{db.location.created}"
    )
    assert saved["locationId"] is None
    assert saved["location"] is None


async def test_the_provenance_group_holds_only_what_the_body_carried(db: _Db) -> None:
    await _save({"location": {**BAGRU, "state": "Rajasthan", "district": "Jaipur"}})

    (written,) = db.location.created
    assert written["latitude"] == BAGRU["latitude"] and written["longitude"] == BAGRU["longitude"]
    assert (written["latitude"], written["longitude"]) != (
        KHARAGPUR["latitude"],
        KHARAGPUR["longitude"],
    ), "the desk's coordinates reached the profile, which is the failure this split exists to end"
    for invented in ("placeName", "address", "capturedAt", "altitude", "accuracy"):
        assert invented not in written, (
            f"the server supplied {invented!r} for a body that did not carry it: "
            f"{written[invented]!r}"
        )
    assert "subjectLatitude" not in written and "subjectLongitude" not in written, (
        "a subject pin was derived from the device fix; they are separate columns precisely so an "
        "export of coordinates is not a silent mixture of the two"
    )


def test_an_address_with_no_coordinate_is_refused_at_the_boundary() -> None:
    with pytest.raises(ValidationError) as caught:
        DesignerProfileUpdate(location={"state": "Rajasthan", "district": "Jaipur"})

    missing = {error["loc"][-1] for error in caught.value.errors()}
    assert {"latitude", "longitude"} <= missing, caught.value.errors()


async def test_an_absent_location_is_kept_and_an_explicit_null_is_refused(db: _Db) -> None:
    first = await _save({"location": {**BAGRU, "state": "Rajasthan", "district": "Jaipur"}})

    kept = await _save({"empanelmentNo": "DCH/EMP/2024/0117"})
    assert kept["locationId"] == first["locationId"], "a save that named no address moved it"
    assert kept["location"]["district"] == "Jaipur"
    assert len(db.location.created) == 1, "a save that named no address wrote a second Location row"

    with pytest.raises(ValidationError):
        DesignerProfileUpdate(location=None)


async def test_the_flat_columns_still_read_on_a_profile_that_has_no_location(db: _Db) -> None:
    saved = await _save(
        {
            "addressLine": "Plot 3, IDCO Institutional Area, Chandaka",
            "city": "Bhubaneswar",
            "state": "Odisha",
            "pincode": "751024",
        }
    )

    assert saved["addressLine"] == "Plot 3, IDCO Institutional Area, Chandaka"
    assert saved["city"] == "Bhubaneswar"
    assert saved["state"] == "Odisha"
    assert saved["pincode"] == "751024"
    assert saved["location"] is None and saved["locationId"] is None


async def test_the_two_addresses_are_returned_side_by_side_and_unmerged(db: _Db) -> None:
    saved = await _save(
        {
            "state": "Odisha",
            "pincode": "751024",
            "location": {**BAGRU, "state": "Rajasthan", "district": "Jaipur", "pincode": "303007"},
        }
    )

    assert saved["state"] == "Odisha", "the location's state was written over the flat column"
    assert saved["pincode"] == "751024"
    assert saved["location"]["state"] == "Rajasthan"
    assert saved["location"]["pincode"] == "303007"


def test_the_profile_body_takes_an_address_and_never_a_row_id() -> None:
    with pytest.raises(ValidationError):
        DesignerProfileUpdate(locationId="loc-1")

    assert "locationId" not in PROFILE_FIELDS, (
        "locationId is in PROFILE_FIELDS, so the write loop would take one off the request body and "
        "two guard tests in test_designer_prefill_contract.py fail with it"
    )


def test_the_route_attaches_the_location_with_the_shared_helper() -> None:
    assert designer_routes.attach_location is attach_location



async def test_the_months_round_trip_beside_the_years(db: _Db) -> None:
    saved = await _save({"experienceYears": 14, "experienceMonths": 6})

    assert saved["experienceYears"] == 14
    assert saved["experienceMonths"] == 6


async def test_zero_months_and_no_answer_stay_different_on_the_wire(db: _Db) -> None:
    absent = await _save({"experienceYears": 14})
    assert absent["experienceMonths"] is None
    assert "experienceMonths" not in DesignerProfileUpdate(experienceYears=14).model_fields_set, (
        "an unsent key counts as set, so exclude_unset cannot protect the stored value"
    )

    zero = await _save({"experienceMonths": 0})
    assert zero["experienceMonths"] == 0, "0 was folded into NULL somewhere on the write path"

    cleared = await _save({"experienceMonths": None})
    assert cleared["experienceMonths"] is None
    assert db.designerprofile.calls[-1]["data"]["update"] == {"experienceMonths": None}, (
        "an explicit null did not reach the write as a null"
    )


@pytest.mark.parametrize("months", [12, 13, 99, -1])
def test_a_month_outside_the_band_is_a_422_naming_the_field(months: int) -> None:
    with pytest.raises(ValidationError) as caught:
        DesignerProfileUpdate(experienceMonths=months)

    (error,) = caught.value.errors()
    assert error["loc"] == ("experienceMonths",), error


@pytest.mark.parametrize("model", [ArtisanUpdate, DesignerProfileUpdate])
@pytest.mark.parametrize("months", [12, -1])
def test_both_record_types_refuse_the_same_out_of_band_month(model: type, months: int) -> None:
    with pytest.raises(ValidationError):
        model(experienceMonths=months)


def test_eleven_months_is_accepted_on_both() -> None:
    assert DesignerProfileUpdate(experienceMonths=11).experienceMonths == 11
    assert ArtisanUpdate(experienceMonths=11).experienceMonths == 11


def test_an_artisan_create_may_leave_the_months_unanswered() -> None:
    created = ArtisanCreate(
        name="Ramesh Chhipa",
        place="Bagru",
        craftName="Block printing",
        aadhaarNumber="234567890124",
        dos="Handle the blocks with dry hands.",
        donts="Do not soak the blocks overnight.",
        experienceYears=30,
        location={**BAGRU, "state": "Rajasthan", "district": "Jaipur"},
    )

    assert created.experienceMonths is None
    assert "experienceMonths" not in created.model_fields_set
    assert "experienceMonths" not in record_service.clean_data(created.model_dump())


def test_the_months_can_be_retracted_on_an_artisan_patch() -> None:
    from app.api.routes.artisans import _CLEARABLE_COLUMNS

    assert "experienceMonths" in _CLEARABLE_COLUMNS
    values = ArtisanUpdate(experienceMonths=None).model_dump(exclude_unset=True)
    assert record_service.clean_data(values, clearable=_CLEARABLE_COLUMNS) == {
        "experienceMonths": None
    }


def test_the_wire_body_still_carries_every_column_the_serializer_reads() -> None:
    assert "experienceMonths" in PROFILE_FIELDS
    assert set(PROFILE_FIELDS) <= set(DesignerProfileUpdate.model_fields)


FORMATTED_ADDRESS = (
    '{"blocks":[{"kind":"PARAGRAPH","spans":'
    '[{"text":"12 Nagar Road, "},{"text":"Bagru","marks":["BOLD"]}]}]}'
)


async def test_an_address_written_before_rich_text_is_stored_and_prefilled_unchanged(
    db: _Db,
) -> None:
    plain = "12 Nagar Road, Bagru"
    saved = await _save({"addressLine": plain})
    assert saved["addressLine"] == plain

    prefilled = await designer_service.prefill_from_profile(USER_ID)
    assert prefilled["designerAddress"] == plain

    devanagari = "१२ नगर रोड, बगरू"
    await _save({"addressLine": devanagari})
    assert (await designer_service.prefill_from_profile(USER_ID))["designerAddress"] == devanagari


async def test_a_formatted_address_is_stored_verbatim_and_reaches_the_report_as_words(
    db: _Db,
) -> None:
    saved = await _save({"addressLine": FORMATTED_ADDRESS})
    assert saved["addressLine"] == FORMATTED_ADDRESS, "the server rewrote a document it was handed"

    prefilled = await designer_service.prefill_from_profile(USER_ID)
    assert prefilled["designerAddress"] == "12 Nagar Road, Bagru"
    assert "blocks" not in prefilled["designerAddress"]
    assert "{" not in prefilled["designerAddress"]


async def test_a_multi_paragraph_address_reaches_the_report_cover_as_one_line(db: _Db) -> None:
    two_blocks = (
        '{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"12 Nagar Road"}]},'
        '{"kind":"PARAGRAPH","spans":[{"text":"Bagru 303007"}]}]}'
    )
    await _save({"addressLine": two_blocks})
    assert (await designer_service.prefill_from_profile(USER_ID))["designerAddress"] == (
        "12 Nagar Road Bagru 303007"
    )

    await _save({"addressLine": "12 Nagar Road\r\nBagru 303007"})
    assert (await designer_service.prefill_from_profile(USER_ID))["designerAddress"] == (
        "12 Nagar Road Bagru 303007"
    )

    await _save({"addressLine": "12 Nagar Road\rBagru"})
    assert (await designer_service.prefill_from_profile(USER_ID))["designerAddress"] == (
        "12 Nagar Road Bagru"
    )


def test_the_address_ceiling_counts_the_designers_words_and_not_the_json_around_them() -> None:
    address_words = ("Flat", "2", "Nagar", "Road", "Bagru", "Jaipur", "Rajasthan", "303007")
    marked = ",".join(
        f'{{"text":"{word} ","marks":["BOLD","ITALIC"]}}' for word in address_words
    )
    document = f'{{"blocks":[{{"kind":"PARAGRAPH","spans":[{marked}]}}]}}'
    assert len(document) > 300, "this fixture no longer exercises the envelope-versus-words split"
    assert DesignerProfileUpdate(addressLine=document).addressLine == document

    with pytest.raises(ValidationError) as refused:
        DesignerProfileUpdate(addressLine="x" * 301)
    assert "report cover" in str(refused.value)

    long_words = "y" * 301
    over_the_line = f'{{"blocks":[{{"kind":"PARAGRAPH","spans":[{{"text":"{long_words}"}}]}}]}}'
    with pytest.raises(ValidationError):
        DesignerProfileUpdate(addressLine=over_the_line)
