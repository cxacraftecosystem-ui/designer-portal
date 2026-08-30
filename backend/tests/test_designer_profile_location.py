"""The designer profile's address and its two-part experience, both on the profile write path.

TWO REQUIREMENTS, ONE FILE, BECAUSE THEY MEET IN ONE FUNCTION. ``update_profile`` is where a stated
address and a months figure both become columns, and both are cases of the same rule: a value the
API can be SENT has to come back the way it was sent, and an answer nobody gave has to stay absent
rather than being manufactured into a plausible one. The two briefs are:

* **requirement 29** — a designer profile carries a ``Location`` like every other record page, so
  the district and the map point have somewhere to live.
* **requirement 14** — experience becomes years AND months, as two stored numbers and never a total.

── WHY THERE IS NO DATABASE HERE ────────────────────────────────────────────────────────────────

Everything asserted below is a decision made in Python — which keys survive ``exclude_unset``, what
``attach_location`` does with a body that has no address, what ``profile_payload`` puts on the wire —
and none of it is a question about Postgres. ``tests/test_designer_roster.py`` already exercises the
real tables and, like the other twenty-eight database modules, skips itself where Docker is not
running. A guard that is skipped precisely when somebody is working offline and adds a third address
column is not a guard, so this one runs everywhere. The stubs below are deliberately narrow: they
implement the exact calls the functions under test make and nothing else, so a second query, or a
write to a table nobody expected, fails loudly here instead of being absorbed.

── THE FINDING THAT PUT THESE RULES IN PLACE ────────────────────────────────────────────────────

All fifteen artisans on the live database that carry a location sit in Kharagpur, West Bengal, while
the places their researchers typed are in Rajasthan, Gujarat, Uttarakhand and Andhra Pradesh: real
GPS fixes of the desk each record was typed at, read afterwards as the subject's address. The
designer profile is the ONE form in this system whose subject is the person filling it in, always
edited from a desk, so it is simultaneously the surface where that stamp is likeliest and the one
where it would look most plausible — "designer based in Kharagpur" is a sentence nobody would
question. That is why the coordinates below are the real ones, and why two of these tests exist
only to prove that the server never invents a third.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import ValidationError

from app.api.routes import designers as designer_routes
from app.schemas.designers import DesignerProfileUpdate
from app.schemas.records import ArtisanCreate, ArtisanUpdate
from app.services import designers as designer_service, records as record_service
from app.services.designers import PROFILE_FIELDS, profile_payload, update_profile
from app.services.records import attach_location

# Bagru, Rajasthan — the block-printing cluster three of the fifteen records name in prose, and the
# place a designer would actually be stating. Used as the coordinate a person DELIBERATELY supplied.
BAGRU = {"latitude": 26.8137, "longitude": 75.545}
# Kharagpur, West Bengal — where all fifteen of them actually are. Never written by anything below;
# it appears only so a test asserting "no coordinate was invented" can name the invented one.
KHARAGPUR = {"latitude": 22.3149, "longitude": 87.3105}

USER_ID = "user-latha"


# --------------------------------------------------------------------------------------
# The stubs
# --------------------------------------------------------------------------------------


class _LocationTable:
    """``db.location`` with only the one call ``attach_location`` makes.

    Every create is recorded, so a test can assert not just what was written but HOW MANY rows were
    written — "no row at all" is the assertion for a save that mentions no address, and a stub that
    silently swallowed a create would make that test pass for the wrong reason.
    """

    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, data: dict[str, Any]) -> SimpleNamespace:
        self.created.append(dict(data))
        row = SimpleNamespace(
            id=f"loc-{len(self.created)}",
            createdAt=datetime(2026, 8, 29, 9, 0, tzinfo=UTC),
            updatedAt=datetime(2026, 8, 29, 9, 0, tzinfo=UTC),
        )
        # Only the keys that were actually written are set. A stub that defaulted the rest to None
        # would hide the difference between "the server stored no coordinate" and "the server stored
        # a null one", which is the distinction half this file is about.
        for key, value in data.items():
            setattr(row, key, value)
        return row


class _ProfileTable:
    """``db.designerprofile`` with only ``upsert``, holding one row per user in memory.

    ``include`` IS HONOURED RATHER THAN IGNORED, which is the point of having a stub here at all: the
    live defect this guards against is a row fetched WITHOUT the relation, whose ``location`` reads
    ``None`` for every designer for ever. A stub that always attached the location would report green
    against exactly that bug.
    """

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
    """The one database both modules under test reach for, patched into both of them.

    ``attach_location`` lives in ``services.records`` and ``update_profile`` in
    ``services.designers``, and each holds its own module-level ``db``. Patching one and not the
    other is how a test of this path ends up half real, so both are patched from a single object and
    the ``Location`` row a save creates is the row the following read hands back.
    """
    stub = _Db()
    monkeypatch.setattr(record_service, "db", stub)
    monkeypatch.setattr(designer_service, "db", stub)
    return stub


async def _save(body: dict[str, Any], *, user_id: str = USER_ID) -> dict[str, Any]:
    """One PUT of the profile, through every step the route takes and in the route's order.

    Deliberately mirrors ``put_my_profile`` rather than calling it: the handler's own work is the
    dependency-injected ``current_user``, which would need a whole app fixture to supply, and the
    three lines below are the entirety of what it does with the body. If that handler grows a fourth
    step, this helper is wrong and has to be updated — which is easier to notice than a passing test
    of a path the application no longer takes.
    """
    payload = DesignerProfileUpdate(**body)
    values = payload.model_dump(exclude_unset=True)
    values = await attach_location(values)
    return profile_payload(await update_profile(user_id, values))


# --------------------------------------------------------------------------------------
# Requirement 29 — the address
# --------------------------------------------------------------------------------------


async def test_a_stated_address_with_its_district_round_trips(db: _Db) -> None:
    """The whole point of the relation: a district goes in and the same district comes back.

    ``DesignerProfile`` had no district column and no coordinate before this change, so a designer
    could state a city and a PIN code and nothing else. The district is the finest administrative
    unit in this system that can be CHECKED against a closed list, and it is validated inside its
    state — "Bilaspur" is a district of Chhattisgarh and a different one of Himachal Pradesh — which
    is why it is asserted here beside the state it was resolved under rather than on its own.
    """
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
    """``include`` is on the READ as well as the write, which is the half that is easy to forget.

    The generated client declares the relation as ``Optional['models.Location'] = None``, so a row
    fetched without ``include`` does not raise — it answers ``None``. A serializer reading that would
    publish ``"location": null`` for every designer, for ever, with nothing on any screen to say the
    address had simply never been asked for. This asserts the include is actually passed, on both
    paths that produce a profile.
    """
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
    """THE FINDING, AS AN ASSERTION. A save with no address writes NO ``Location`` row.

    Not a row with a sentinel coordinate, not a row defaulted to 0,0, and not a row carrying the
    Kharagpur fix of whatever desk the request came from — no row at all. ``attach_location``'s first
    line is ``if location:``, so a body that never mentions an address leaves ``locationId`` NULL, and
    NULL is the honest answer for a designer who has not said where they are.

    This is the test that fails if somebody "solves" the not-null problem by manufacturing a
    coordinate so a state and a district can be stored without one. That is not a solution; it is the
    original defect with a nicer motive.
    """
    saved = await _save({"displayName": "Latha Nayak", "city": "Bhubaneswar", "state": "Odisha"})

    assert db.location.created == [], (
        f"a profile save with no address created {len(db.location.created)} Location row(s): "
        f"{db.location.created}"
    )
    assert saved["locationId"] is None
    assert saved["location"] is None


async def test_the_provenance_group_holds_only_what_the_body_carried(db: _Db) -> None:
    """Nothing on this path geocodes, derives or defaults the provenance half of a location.

    The two groups on ``Location`` answer different questions — PROVENANCE (``latitude``,
    ``longitude``, ``altitude``, ``accuracy``, ``capturedAt``, ``placeName``, ``address``) is where
    the DEVICE was; STATED (``state``, ``district``, ``village``, ``pincode``, ``subjectLatitude``,
    ``subjectLongitude``) is where the SUBJECT is — and a designer's own address is a STATED address
    throughout. The server cannot tell a deliberately pressed GPS from an automatic one, since both
    arrive as two floats, so what it CAN promise is that it adds nothing: every provenance key on the
    written row was in the request, or is absent from the row entirely.
    """
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
    """The not-null problem, answered exactly the way the six field-record types answer it.

    ``Location.latitude``/``longitude`` are NOT NULL and ``LocationInput`` declares them as required
    floats with no default, so a body naming a state and a district and carrying no point is a 422
    before any handler runs. That is a REFUSAL and not a gap: the only alternative is inventing a
    coordinate, and relaxing the columns to avoid it would weaken the invariant on all six other
    owners to save one screen a click. The web card says as much in the box rather than as a round
    trip — "The state and district are stored with the coordinates, so this record needs one before
    they can be saved."
    """
    with pytest.raises(ValidationError) as caught:
        DesignerProfileUpdate(location={"state": "Rajasthan", "district": "Jaipur"})

    missing = {error["loc"][-1] for error in caught.value.errors()}
    assert {"latitude", "longitude"} <= missing, caught.value.errors()


async def test_an_absent_location_is_kept_and_an_explicit_null_is_refused(db: _Db) -> None:
    """Absent means keep; null means no. The same rule the six record bodies carry.

    ``attach_location`` writes a BRAND NEW ``Location`` row on every save and never updates one, so
    "clear it" has no honest implementation — it would orphan the stored row and leave the profile
    with no district rather than with a corrected address. A designer who moves house REPLACES their
    location. The absent half is what lets an admin's two-key PUT of the empanelment identifiers
    leave the designer's address alone.
    """
    first = await _save({"location": {**BAGRU, "state": "Rajasthan", "district": "Jaipur"}})

    kept = await _save({"empanelmentNo": "DCH/EMP/2024/0117"})
    assert kept["locationId"] == first["locationId"], "a save that named no address moved it"
    assert kept["location"]["district"] == "Jaipur"
    assert len(db.location.created) == 1, "a save that named no address wrote a second Location row"

    with pytest.raises(ValidationError):
        DesignerProfileUpdate(location=None)


async def test_the_flat_columns_still_read_on_a_profile_that_has_no_location(db: _Db) -> None:
    """The live rows' addresses are in the flat columns, and nothing has been backfilled.

    The migration that added the relation deliberately copied nothing across, because a ``Location``
    row cannot be manufactured out of four text columns without inventing the coordinate. So both
    places are live, both are on the wire, and this asserts the older one still answers for a
    designer who has never given a point — which describes EVERY row on the database today. A client
    that renders only ``location`` shows those designers a blank where their address is.
    """
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
    """Neither address is silently preferred, and the payload can always say which is which.

    A merge would need a precedence rule, and every precedence rule invents an answer for the rows
    where the two disagree — a designer who moved and corrected one of the two forms — while a merged
    payload cannot say which side it came from. So the flat state stays the flat state even when the
    location states something else, and it is the CLIENT that decides what to show.
    """
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
    """``locationId`` is written by the server and is not a key any client may send.

    A body that accepted a raw id would let any caller point their profile at another record's
    ``Location`` row, and would make "the address" two round trips that can disagree. ``APIModel``
    sets ``extra="forbid"``, so the refusal is a 422 rather than a silently ignored key — and
    ``PROFILE_FIELDS`` deliberately does not name the column, which is what keeps ``update_profile``'s
    loop from ever taking one off a request.
    """
    with pytest.raises(ValidationError):
        DesignerProfileUpdate(locationId="loc-1")

    assert "locationId" not in PROFILE_FIELDS, (
        "locationId is in PROFILE_FIELDS, so the write loop would take one off the request body and "
        "two guard tests in test_designer_prefill_contract.py fail with it"
    )


def test_the_route_attaches_the_location_with_the_shared_helper() -> None:
    """The helper is the SHARED one, imported rather than reimplemented.

    ``attach_location`` is what the six field-record routes call, and calling that same function is
    the whole reason a profile address behaves like an artisan address: the district validation, the
    ``extraMetadata`` lift the installed Android fleet still depends on, and the ``Json`` wrapping
    that stops a phone's create from being a 500. A private copy here would drift from all three.
    """
    assert designer_routes.attach_location is attach_location


# --------------------------------------------------------------------------------------
# Requirement 14 — years AND months
# --------------------------------------------------------------------------------------


async def test_the_months_round_trip_beside_the_years(db: _Db) -> None:
    """Two numbers in, the same two numbers out. Never a total.

    A single stored 66 cannot tell "5 years and 6 months" from "66 months" from "five and a half
    years", and re-dividing it on every read would make ``experienceYears`` — a column four export
    surfaces read and every submitted participant table prints — a derived value overnight.
    """
    saved = await _save({"experienceYears": 14, "experienceMonths": 6})

    assert saved["experienceYears"] == 14
    assert saved["experienceMonths"] == 6


async def test_zero_months_and_no_answer_stay_different_on_the_wire(db: _Db) -> None:
    """NULL and 0 are two different statements, and the whole path keeps them apart.

    Every profile stored before this column existed answered a form that asked for years alone. If
    absent folded into 0, all of them would start claiming "and no months" as a fact somebody
    entered, and the second dropdown would open pre-answered on a profile whose owner was never shown
    the question. Three answers, three outcomes: absent leaves the stored value, an explicit null
    clears it, and 0 stores 0.
    """
    # ABSENT — the years are saved and the months are never mentioned, so the column keeps NULL.
    absent = await _save({"experienceYears": 14})
    assert absent["experienceMonths"] is None
    assert "experienceMonths" not in DesignerProfileUpdate(experienceYears=14).model_fields_set, (
        "an unsent key counts as set, so exclude_unset cannot protect the stored value"
    )

    # ZERO — a real answer, and it must survive every fold on the way in.
    zero = await _save({"experienceMonths": 0})
    assert zero["experienceMonths"] == 0, "0 was folded into NULL somewhere on the write path"

    # NULL — how a designer un-answers it, which has to reach the column as a null.
    cleared = await _save({"experienceMonths": None})
    assert cleared["experienceMonths"] is None
    assert db.designerprofile.calls[-1]["data"]["update"] == {"experienceMonths": None}, (
        "an explicit null did not reach the write as a null"
    )


@pytest.mark.parametrize("months", [12, 13, 99, -1])
def test_a_month_outside_the_band_is_a_422_naming_the_field(months: int) -> None:
    """The bound is on the BODY, not only on the ``CHECK`` constraint, and the difference is visible.

    The column carries ``CHECK (experienceMonths BETWEEN 0 AND 11)``. A CHECK violation reaches this
    API as a driver error raised from inside the write — a bare 500 that names no field, on a save
    the designer cannot correct because nothing on the screen says which box was wrong. What is
    asserted here is the other thing: a refusal that carries the field name, which is what the
    clients turn into a message under the control.
    """
    with pytest.raises(ValidationError) as caught:
        DesignerProfileUpdate(experienceMonths=months)

    (error,) = caught.value.errors()
    assert error["loc"] == ("experienceMonths",), error


@pytest.mark.parametrize("model", [ArtisanUpdate, DesignerProfileUpdate])
@pytest.mark.parametrize("months", [12, -1])
def test_both_record_types_refuse_the_same_out_of_band_month(model: type, months: int) -> None:
    """The artisan and the designer answer the same question, so they must refuse the same values.

    Two ceilings for one concept is how a number the artisan form accepts becomes a number the
    designer profile rejects, on a control both screens draw identically.
    """
    with pytest.raises(ValidationError):
        model(experienceMonths=months)


def test_eleven_months_is_accepted_on_both() -> None:
    """The top of the band is IN the band.

    A remainder of eleven is the commonest non-zero answer there is, and an off-by-one here would
    refuse it while accepting ten.
    """
    assert DesignerProfileUpdate(experienceMonths=11).experienceMonths == 11
    assert ArtisanUpdate(experienceMonths=11).experienceMonths == 11


def test_an_artisan_create_may_leave_the_months_unanswered() -> None:
    """An artisan who says "about thirty years" has said nothing whatever about months.

    ``ArtisanCreate`` also requires a name, a place, a craft, an Aadhaar, do's, don'ts and a
    location, and none of them has anything to do with this column — the body is complete here
    because pydantic reports missing fields BEFORE a model validator runs, so a partial body would
    make this test pass without ever reaching the default under test. What matters is that the
    months default to ``None`` rather than 0, so ``clean_data`` drops the key entirely and the row
    keeps NULL.
    """
    created = ArtisanCreate(
        name="Ramesh Chhipa",
        place="Bagru",
        craftName="Block printing",
        # Checksum-valid (Verhoeff) and not starting with 0 or 1, copied from the fixture in
        # tests/test_android_location_compat.py. An invalid number is refused by its own field
        # validator before anything else runs, which is how a test like this passes for the wrong
        # reason.
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
    """An explicit null on the PATCH has to reach the column, or the save is a 200 that does nothing.

    ``clean_data`` drops keys whose value is ``None`` — which is what stops a create from writing an
    explicit NULL for every box the researcher left blank — so a column that must be CLEARABLE has to
    be named in ``_CLEARABLE_COLUMNS``. Without the entry the form shows the box empty, the save
    reports success, and the old months figure is still in the database.
    """
    from app.api.routes.artisans import _CLEARABLE_COLUMNS

    assert "experienceMonths" in _CLEARABLE_COLUMNS
    values = ArtisanUpdate(experienceMonths=None).model_dump(exclude_unset=True)
    assert record_service.clean_data(values, clearable=_CLEARABLE_COLUMNS) == {
        "experienceMonths": None
    }


def test_the_wire_body_still_carries_every_column_the_serializer_reads() -> None:
    """The join between the hand-maintained lists, re-asserted for the column just added.

    ``PROFILE_FIELDS`` drives both loops in ``services/designers.py``; a name present there and
    absent from ``DesignerProfileUpdate`` is a column that serialises OUT and can never be written
    IN — readable, permanently empty, and prefilled as blank into every report. The same claim is
    made for the whole tuple in ``test_designer_prefill_contract``; it is made here for the column
    this change added, because that is the one a reader of this file is checking.
    """
    assert "experienceMonths" in PROFILE_FIELDS
    assert set(PROFILE_FIELDS) <= set(DesignerProfileUpdate.model_fields)
