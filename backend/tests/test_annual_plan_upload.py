import asyncio
import uuid
from datetime import UTC, date, datetime
from io import BytesIO
from typing import Any

import pytest
from conftest import needs_db
from openpyxl import Workbook, load_workbook

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import annual_plan
from app.services.annual_plan_xlsx import _PRO_FORMA_COLUMNS, MAX_PLAN_ROWS

pytestmark = [needs_db]

HEADINGS = [label for _key, label in _PRO_FORMA_COLUMNS]
PASSWORD = "annual-plan-ministry-admin-password"
YEAR = 2026

_VENUE_INDEX = [key for key, _label in _PRO_FORMA_COLUMNS].index("venue")

_FIRST_PLAN_YEAR = 2090

_CASES: tuple[tuple[str, str], ...] = (
    ("first_upload", "test_a_first_upload_creates_every_row_and_reports_the_count"),
    ("twice", "test_uploading_the_same_sheet_twice_writes_nothing_the_second_time"),
    ("corrected", "test_a_corrected_sheet_updates_only_the_rows_that_changed"),
    ("resorted", "test_a_re_sorted_sheet_that_changes_nothing_writes_nothing"),
    ("field_by_field", "test_a_change_is_reported_field_by_field_with_before_and_after"),
    ("respelled", "test_a_re_spelled_workshop_number_updates_the_row_it_already_wrote"),
    ("absent_default", "test_a_row_missing_from_a_later_sheet_is_left_alone_by_default"),
    ("withdraw_asked", "test_a_row_missing_from_a_later_sheet_is_withdrawn_only_when_asked"),
    (
        "filtered_export",
        "test_a_filtered_export_cannot_be_used_to_withdraw_the_rows_its_filter_hid",
    ),
    ("whole_year_export", "test_the_whole_year_export_is_still_allowed_to_withdraw"),
    ("reinstated", "test_a_withdrawn_row_that_reappears_is_reinstated_and_reported"),
    ("year_mismatch", "test_a_sheet_naming_one_year_uploaded_as_another_is_a_409_naming_both"),
    ("no_year", "test_a_sheet_that_names_no_year_and_no_year_chosen_is_a_422"),
    ("rollback", "test_a_failure_after_the_creates_leaves_nothing_written"),
    ("report_keys", "test_the_report_names_every_count_the_screen_draws"),
    ("counts_add_up", "test_the_counts_add_up_to_the_rows_read"),
    ("truncated", "test_the_change_list_says_when_it_was_truncated"),
    ("ceiling", "test_the_ceiling_is_the_parsers_and_the_write_never_sees_more"),
    ("non_workbook", "test_a_non_workbook_is_refused_before_a_byte_of_it_is_read"),
    ("designer_door", "test_a_designer_may_not_upload_the_ministrys_plan"),
    ("date_as_text", "test_a_date_typed_as_text_is_stored_as_the_day_it_reads"),
)

_PLAN_YEARS: dict[str, int] = {
    key: _FIRST_PLAN_YEAR - 2 * index for index, (key, _test) in enumerate(_CASES)
}

_YEAR_BY_TEST: dict[str, int] = {
    test: _FIRST_PLAN_YEAR - 2 * index for index, (_key, test) in enumerate(_CASES)
}


def _excel_datetime(year: int, month: int, day: int) -> datetime:
    return datetime(year, month, day)  # noqa: DTZ001


def _headers(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _row(**overrides: Any) -> list[Any]:
    values: dict[str, Any] = {key: None for key, _label in _PRO_FORMA_COLUMNS}
    values.update(overrides)
    return [values[key] for key, _label in _PRO_FORMA_COLUMNS]


def _book(rows: list[list[Any]], *, plan_year: int | None = None) -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = "Annual plan"
    ws.append(HEADINGS)
    for row in rows:
        ws.append(list(row))
    if plan_year is not None:
        details = wb.create_sheet(title="Details")
        details.append(["Plan year", str(plan_year)])
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def _three_hundred(prefix: str) -> list[list[Any]]:
    return [
        _row(
            workshopNo=f"{prefix}/{n:03d}",
            plannedTitle=f"Workshop {n}",
            state="Gujarat",
            district="Kachchh",
            venue=f"Venue {n}",
            plannedStartDate=_excel_datetime(2026, 3, 1 + (n % 28)),
        )
        for n in range(1, 301)
    ]


def _post(
    client: Any,
    headers: dict[str, str],
    data: bytes,
    *,
    plan_year: int | None = None,
    withdraw: bool | None = None,
):
    form: dict[str, str] = {}
    if plan_year is not None:
        form["planYear"] = str(plan_year)
    if withdraw:
        form["withdrawAbsent"] = "true"
    return client.post(
        "/api/annual-plan/upload",
        files={"file": ("plan.xlsx", data, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
        data=form,
        headers=headers,
    )


def _upload(world, data: bytes, *, plan_year: int | None = None, withdraw: bool | None = None):
    return _post(
        world["client"], _headers(world), data, plan_year=plan_year, withdraw=withdraw
    )


async def _rows(plan_year: int) -> dict[str, Any]:
    found = await db.annualplanentry.find_many(where={"planYear": plan_year})
    return {row.workshopNoKey: row for row in found}


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"annual-plan-admin-{stamp}@example.org"

    years = _PLAN_YEARS
    facts: dict[str, Any] = {"stamp": stamp, "years": years, "rollback_error": None}
    answers: dict[str, Any] = {}
    rows: dict[str, Any] = {}
    books: dict[str, bytes] = {}
    facts["answers"] = answers
    facts["rows"] = rows

    async def seed() -> None:
        await db.connect()
        try:
            facts["admin"] = await db.user.create(
                data={
                    "email": email,
                    "name": f"Ministry admin {stamp}",
                    "role": "MINISTRY_ADMIN",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": email,
                    "status": "ACTIVE",
                    "admitRole": "MINISTRY_ADMIN",
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_annual_plan_upload.py.",
                }
            )
            facts["designer"] = await db.user.create(
                data={
                    "email": f"plan-designer-{uuid.uuid4().hex[:8]}@example.org",
                    "name": "A designer",
                    "role": "DESIGNER",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            for chosen in years.values():
                await db.annualplanentry.delete_many(where={"planYear": chosen})
                await db.annualplanentry.delete_many(where={"planYear": chosen - 1})
        finally:
            await db.disconnect()

    def act_one(client: Any) -> None:
        headers = _headers(facts)

        def post(data: bytes, *, plan_year: int | None = None, withdraw: bool | None = None):
            return _post(client, headers, data, plan_year=plan_year, withdraw=withdraw)

        year = years["first_upload"]
        answers["first_upload"] = post(_book(_three_hundred(f"DPW/{year}")), plan_year=year)

        year = years["twice"]
        books["twice"] = _book(_three_hundred(f"DPW/{year}"))
        answers["twice_first"] = post(books["twice"], plan_year=year)

        year = years["corrected"]
        corrected = _three_hundred(f"DPW/{year}")
        answers["corrected_first"] = post(_book(corrected), plan_year=year)
        corrected[16][_VENUE_INDEX] = "DIC Hall, Bhuj"
        answers["corrected_second"] = post(_book(corrected), plan_year=year)

        year = years["resorted"]
        answers["resorted_first"] = post(_book(_three_hundred(f"DPW/{year}")), plan_year=year)

        year = years["respelled"]
        prefix = f"DPW/{year}"
        answers["respelled_first"] = post(_book([_row(workshopNo=f"{prefix}/017")]), plan_year=year)
        answers["respelled_second"] = post(
            _book([_row(workshopNo=f"{prefix}/017".lower())]), plan_year=year
        )

        year = years["absent_default"]
        prefix = f"DPW/{year}"
        pair = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
        answers["absent_default_first"] = post(_book(pair), plan_year=year)
        answers["absent_default_second"] = post(_book(pair[:1]), plan_year=year)

        year = years["withdraw_asked"]
        prefix = f"DPW/{year}"
        pair = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
        answers["withdraw_asked_first"] = post(_book(pair), plan_year=year)
        answers["withdraw_asked_second"] = post(_book(pair[:1]), plan_year=year, withdraw=True)

        year = years["filtered_export"]
        prefix = f"DPW/{year}"
        whole_year = [_row(workshopNo=f"{prefix}/{n:03d}", state="Gujarat") for n in (1, 2, 3)]
        answers["filtered_export_first"] = post(_book(whole_year), plan_year=year)
        answers["filtered_export"] = client.get(
            f"/api/annual-plan/export.xlsx?planYear={year}&search={prefix}/001",
            headers=headers,
        )
        answers["filtered_refused"] = post(
            answers["filtered_export"].content, plan_year=year, withdraw=True
        )

        year = years["reinstated"]
        prefix = f"DPW/{year}"
        both = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
        answers["reinstated_first"] = post(_book(both), plan_year=year)
        answers["reinstated_withdraw"] = post(_book(both[:1]), plan_year=year, withdraw=True)
        answers["reinstated_third"] = post(_book(both), plan_year=year)

        year = years["year_mismatch"]
        answers["year_mismatch"] = post(
            _book([_row(workshopNo=f"DPW/{year}/001")], plan_year=year - 1), plan_year=year
        )

        year = years["no_year"]
        answers["no_year"] = post(_book([_row(workshopNo=f"DPW/{year}/001")]))

        year = years["rollback"]
        prefix = f"DPW/{year}"
        answers["rollback_first"] = post(
            _book(
                [
                    _row(workshopNo=f"{prefix}/001", venue="A"),
                    _row(workshopNo=f"{prefix}/002", venue="B"),
                ]
            ),
            plan_year=year,
        )

        year = years["ceiling"]
        prefix = f"DPW/{year}"
        answers["ceiling"] = post(
            _book([_row(workshopNo=f"{prefix}/{n:04d}") for n in range(MAX_PLAN_ROWS + 3)]),
            plan_year=year,
        )

        year = years["date_as_text"]
        answers["date_as_text"] = post(
            _book([_row(workshopNo=f"DPW/{year}/001", plannedStartDate="03/04/2026")]),
            plan_year=year,
        )

    async def observe_one() -> None:
        await db.connect()
        try:
            rows["first_upload"] = await _rows(years["first_upload"])
            rows["twice_before"] = await _rows(years["twice"])
            rows["resorted_before"] = await _rows(years["resorted"])
            rows["rollback_before"] = await _rows(years["rollback"])
            rows["corrected"] = await _rows(years["corrected"])
            rows["respelled"] = await _rows(years["respelled"])
            rows["absent_default"] = await _rows(years["absent_default"])
            rows["withdraw_asked"] = await _rows(years["withdraw_asked"])
            rows["filtered_refused"] = await _rows(years["filtered_export"])
            rows["reinstated"] = await _rows(years["reinstated"])
            rows["year_mismatch"] = await _rows(years["year_mismatch"])
            rows["year_mismatch_named"] = await _rows(years["year_mismatch"] - 1)
            rows["no_year"] = await _rows(years["no_year"])
            rows["ceiling"] = await _rows(years["ceiling"])
            rows["date_as_text"] = await _rows(years["date_as_text"])
        finally:
            await db.disconnect()

    def act_two(client: Any) -> None:
        headers = _headers(facts)

        def post(data: bytes, *, plan_year: int | None = None, withdraw: bool | None = None):
            return _post(client, headers, data, plan_year=plan_year, withdraw=withdraw)

        answers["twice_second"] = post(books["twice"], plan_year=years["twice"])

        year = years["resorted"]
        answers["resorted_second"] = post(
            _book(list(reversed(_three_hundred(f"DPW/{year}")))), plan_year=year
        )

        answers["filtered_accepted"] = post(
            answers["filtered_export"].content, plan_year=years["filtered_export"]
        )

        year = years["rollback"]
        prefix = f"DPW/{year}"
        calls = {"n": 0}
        original = annual_plan._write_entry_update

        async def _explode(tx, entry_id, data):
            calls["n"] += 1
            if calls["n"] == 2:
                raise RuntimeError("provoked after the creates landed")
            return await original(tx, entry_id, data)

        bigger = [
            _row(workshopNo=f"{prefix}/001", venue="A2"),
            _row(workshopNo=f"{prefix}/002", venue="B2"),
            _row(workshopNo=f"{prefix}/003"),
            _row(workshopNo=f"{prefix}/004"),
            _row(workshopNo=f"{prefix}/005"),
        ]
        annual_plan._write_entry_update = _explode
        try:
            answers["rollback_second"] = post(_book(bigger), plan_year=year)
        except Exception as exc:  # noqa: BLE001 - the CLASS is the finding; see the test
            facts["rollback_error"] = exc
        finally:
            annual_plan._write_entry_update = original

    async def observe_two() -> None:
        await db.connect()
        try:
            rows["twice_after"] = await _rows(years["twice"])
            rows["resorted_after"] = await _rows(years["resorted"])
            rows["filtered_accepted"] = await _rows(years["filtered_export"])
            rows["rollback_after"] = await _rows(years["rollback"])
        finally:
            await db.disconnect()

    asyncio.run(seed())
    with TestClient(app) as client:
        act_one(client)
    asyncio.run(observe_one())
    with TestClient(app) as client:
        act_two(client)
    asyncio.run(observe_two())
    with TestClient(app) as client:
        facts["client"] = client
        yield facts


@pytest.fixture
def year(world, request) -> int:
    chosen = _YEAR_BY_TEST.get(request.node.name)
    if chosen is None:
        raise AssertionError(
            f"{request.node.name} has no entry in _CASES, so no plan year was allocated for it and "
            "no year of its own was emptied. Add it there rather than borrowing a neighbour's."
        )
    return chosen



def test_a_first_upload_creates_every_row_and_reports_the_count(world, year) -> None:
    response = world["answers"]["first_upload"]
    assert response.status_code == 201, response.text
    report = response.json()
    assert report["rowsRead"] == 300
    assert report["created"] == 300
    assert report["updated"] == 0
    assert report["unchanged"] == 0
    assert report["planYearLabel"] == annual_plan.plan_year_label(year)
    assert len(world["rows"]["first_upload"]) == 300


def test_uploading_the_same_sheet_twice_writes_nothing_the_second_time(world) -> None:
    assert world["answers"]["twice_first"].status_code == 201
    before = {
        key: (row.updatedAt, row.revision) for key, row in world["rows"]["twice_before"].items()
    }

    report = world["answers"]["twice_second"].json()
    assert report["unchanged"] == 300
    assert report["updated"] == 0
    assert report["created"] == 0

    after = {
        key: (row.updatedAt, row.revision) for key, row in world["rows"]["twice_after"].items()
    }
    assert after == before


def test_a_corrected_sheet_updates_only_the_rows_that_changed(world, year) -> None:
    prefix = f"DPW/{year}"
    assert world["answers"]["corrected_first"].status_code == 201

    report = world["answers"]["corrected_second"].json()

    assert report["updated"] == 1
    assert report["unchanged"] == 299
    stored = world["rows"]["corrected"]
    assert stored[f"{prefix}/017".upper()].revision == 2
    assert all(row.revision == 1 for key, row in stored.items() if not key.endswith("/017"))


def test_a_re_sorted_sheet_that_changes_nothing_writes_nothing(world) -> None:
    assert world["answers"]["resorted_first"].status_code == 201
    before = {
        key: (row.updatedAt, row.sheetRow) for key, row in world["rows"]["resorted_before"].items()
    }

    report = world["answers"]["resorted_second"].json()
    assert report["unchanged"] == 300
    assert report["updated"] == 0

    after = {
        key: (row.updatedAt, row.sheetRow) for key, row in world["rows"]["resorted_after"].items()
    }
    assert after == before


def test_a_change_is_reported_field_by_field_with_before_and_after(world, year) -> None:
    prefix = f"DPW/{year}"
    rows = [_row(workshopNo=f"{prefix}/001", venue="Kala Bhavan, Bhuj")]
    assert _upload(world, _book(rows), plan_year=year).status_code == 201

    rows[0] = _row(workshopNo=f"{prefix}/001", venue="DIC Hall, Bhuj")
    report = _upload(world, _book(rows), plan_year=year).json()
    assert report["changes"] == [
        {
            "workshopNo": f"{prefix}/001",
            "sheetRow": 2,
            "field": "venue",
            "fieldLabel": "Venue",
            "from": "Kala Bhavan, Bhuj",
            "to": "DIC Hall, Bhuj",
            "reason": None,
        }
    ]
    assert report["changesTruncated"] is False


def test_a_re_spelled_workshop_number_updates_the_row_it_already_wrote(world, year) -> None:
    prefix = f"DPW/{year}"
    assert world["answers"]["respelled_first"].status_code == 201
    report = world["answers"]["respelled_second"].json()

    stored = world["rows"]["respelled"]
    assert len(stored) == 1
    row = next(iter(stored.values()))
    assert row.workshopNo == f"{prefix}/017".lower()
    assert row.revision == 2
    assert report["updated"] == 1
    assert report["created"] == 0



def test_a_row_missing_from_a_later_sheet_is_left_alone_by_default(world, year) -> None:
    prefix = f"DPW/{year}"
    assert world["answers"]["absent_default_first"].status_code == 201

    report = world["answers"]["absent_default_second"].json()
    assert report["absent"] == 1
    assert report["withdrawn"] == 0
    assert report["withdrawAbsentRequested"] is False
    stored = world["rows"]["absent_default"]
    assert stored[f"{prefix}/002".upper()].withdrawnAt is None


def test_a_row_missing_from_a_later_sheet_is_withdrawn_only_when_asked(world, year) -> None:
    prefix = f"DPW/{year}"
    assert world["answers"]["withdraw_asked_first"].status_code == 201

    report = world["answers"]["withdraw_asked_second"].json()
    assert report["withdrawn"] == 1
    stored = world["rows"]["withdraw_asked"]
    gone = stored[f"{prefix}/002".upper()]
    assert gone.withdrawnAt is not None
    assert gone.withdrawnById == world["admin"].id
    assert len(stored) == 2, "withdrawn is a stamp, never a delete"


def test_a_filtered_export_cannot_be_used_to_withdraw_the_rows_its_filter_hid(world, year) -> None:
    prefix = f"DPW/{year}"
    assert world["answers"]["filtered_export_first"].status_code == 201

    exported = world["answers"]["filtered_export"]
    assert exported.status_code == 200, exported.text

    refused = world["answers"]["filtered_refused"]
    assert refused.status_code == 422, refused.text
    detail = refused.json()["detail"]
    assert "filtered view" in detail.lower()
    assert f"{prefix}/001" in detail, "the refusal must name the filter the sheet declares"

    stored = world["rows"]["filtered_refused"]
    assert [row.withdrawnAt for row in stored.values()] == [None, None, None], (
        "the filtered sheet withdrew the rows its own filter hid"
    )

    accepted = world["answers"]["filtered_accepted"]
    assert accepted.status_code == 201, accepted.text
    assert accepted.json()["withdrawn"] == 0
    assert len(world["rows"]["filtered_accepted"]) == 3


def test_the_whole_year_export_is_still_allowed_to_withdraw(world, year) -> None:
    prefix = f"DPW/{year}"
    assert (
        _upload(
            world,
            _book([_row(workshopNo=f"{prefix}/{n:03d}") for n in (1, 2)]),
            plan_year=year,
        ).status_code
        == 201
    )
    exported = world["client"].get(
        f"/api/annual-plan/export.xlsx?planYear={year}", headers=_headers(world)
    )
    assert exported.status_code == 200

    book = load_workbook(BytesIO(exported.content))
    book["Annual plan"].delete_rows(3)
    buffer = BytesIO()
    book.save(buffer)

    report = _upload(world, buffer.getvalue(), plan_year=year, withdraw=True)
    assert report.status_code == 201, report.text
    assert report.json()["withdrawn"] == 1


def test_a_withdrawn_row_that_reappears_is_reinstated_and_reported(world, year) -> None:
    prefix = f"DPW/{year}"
    assert world["answers"]["reinstated_first"].status_code == 201
    assert world["answers"]["reinstated_withdraw"].status_code == 201

    report = world["answers"]["reinstated_third"].json()
    assert report["reinstated"] == 1
    assert report["updated"] >= 1
    stored = world["rows"]["reinstated"]
    assert stored[f"{prefix}/002".upper()].withdrawnAt is None
    assert stored[f"{prefix}/002".upper()].withdrawnById is None



def test_a_sheet_naming_one_year_uploaded_as_another_is_a_409_naming_both(world, year) -> None:
    response = world["answers"]["year_mismatch"]
    assert response.status_code == 409
    assert annual_plan.plan_year_label(year) in response.json()["detail"]
    assert annual_plan.plan_year_label(year - 1) in response.json()["detail"]
    assert world["rows"]["year_mismatch"] == {}
    assert world["rows"]["year_mismatch_named"] == {}


def test_a_sheet_that_names_no_year_and_no_year_chosen_is_a_422(world) -> None:
    response = world["answers"]["no_year"]
    assert response.status_code == 422
    assert "which year" in response.json()["detail"]
    assert world["rows"]["no_year"] == {}



def test_a_failure_after_the_creates_leaves_nothing_written(world) -> None:
    assert world["answers"]["rollback_first"].status_code == 201
    before = {
        key: (row.updatedAt, row.revision) for key, row in world["rows"]["rollback_before"].items()
    }

    raised = world["rollback_error"]
    answered = world["answers"].get("rollback_second")
    if raised is None:
        assert answered is not None and answered.status_code == 500, (
            "the provoked failure neither raised nor produced a 500, so nothing was provoked and "
            "the rollback assertions below would pass vacuously"
        )
        assert answered.json().get("error") == "RuntimeError", (
            f"the 500 does not name the provoked RuntimeError: {answered.text[:200]}"
        )
    else:
        assert isinstance(raised, RuntimeError), (
            f"the upload failed with something other than the provoked error: {raised!r}"
        )

    stored = world["rows"]["rollback_after"]
    assert len(stored) == 2, "no created row may survive the rollback"
    assert {key: (row.updatedAt, row.revision) for key, row in stored.items()} == before



def test_the_report_names_every_count_the_screen_draws(world, year) -> None:
    report = _upload(
        world, _book([_row(workshopNo=f"DPW/{year}/001")]), plan_year=year
    ).json()
    for key in (
        "rowsRead",
        "created",
        "updated",
        "unchanged",
        "reinstated",
        "withdrawn",
        "absent",
        "absentPromoted",
        "updatedAfterPromotion",
        "changesTruncated",
        "withdrawAbsentRequested",
    ):
        assert key in report, key


def test_the_counts_add_up_to_the_rows_read(world, year) -> None:
    prefix = f"DPW/{year}"
    first = [_row(workshopNo=f"{prefix}/{n:03d}", venue=f"V{n}") for n in range(1, 6)]
    assert _upload(world, _book(first), plan_year=year).status_code == 201
    assert _upload(world, _book(first[:4]), plan_year=year, withdraw=True).status_code == 201

    second = [
        _row(workshopNo=f"{prefix}/001", venue="CHANGED"),
        _row(workshopNo=f"{prefix}/002", venue="V2"),
        _row(workshopNo=f"{prefix}/005", venue="V5"),
        _row(workshopNo=f"{prefix}/006"),
    ]
    report = _upload(world, _book(second), plan_year=year, withdraw=True).json()

    assert report["created"] + report["updated"] + report["unchanged"] == report["rowsRead"]
    assert report["reinstated"] <= report["updated"]
    assert report["absent"] >= report["absentPromoted"]
    assert report["withdrawn"] <= report["absent"] - report["absentPromoted"]


def test_the_change_list_says_when_it_was_truncated(world, year) -> None:
    cap = annual_plan.MAX_REPORTED_CHANGES
    prefix = f"DPW/{year}"
    rows = [_row(workshopNo=f"{prefix}/{n:04d}", venue=f"V{n}") for n in range(cap + 5)]
    assert _upload(world, _book(rows), plan_year=year).status_code == 201

    changed = [_row(workshopNo=f"{prefix}/{n:04d}", venue=f"W{n}") for n in range(cap + 5)]
    report = _upload(world, _book(changed), plan_year=year).json()
    assert report["changesTruncated"] is True
    assert len(report["changes"]) == cap


def test_the_ceiling_is_the_parsers_and_the_write_never_sees_more(world) -> None:
    report = world["answers"]["ceiling"].json()
    assert report["rowsRead"] == MAX_PLAN_ROWS
    assert len(world["rows"]["ceiling"]) == MAX_PLAN_ROWS
    assert any(p["severity"] == "error" for p in report["problems"])



def test_a_non_workbook_is_refused_before_a_byte_of_it_is_read(world, year) -> None:
    response = world["client"].post(
        "/api/annual-plan/upload",
        files={"file": ("plan.docx", b"PK\x03\x04nonsense", "application/octet-stream")},
        data={"planYear": str(year)},
        headers=_headers(world),
    )
    assert response.status_code == 415
    assert "Excel workbook" in response.json()["detail"]


def test_a_designer_may_not_upload_the_ministrys_plan(world, year) -> None:
    designer = world["designer"]
    response = world["client"].post(
        "/api/annual-plan/upload",
        files={"file": ("plan.xlsx", _book([_row(workshopNo="DPW/1/1")]), "application/octet-stream")},
        data={"planYear": str(year)},
        headers={"Authorization": f"Bearer {create_access_token(subject=designer.id)}"},
    )
    assert response.status_code == 403
    assert "ministry administrator" in response.json()["detail"].lower()


def test_a_date_typed_as_text_is_stored_as_the_day_it_reads(world) -> None:
    assert world["answers"]["date_as_text"].status_code == 201
    stored = next(iter(world["rows"]["date_as_text"].values()))
    assert stored.plannedStartDate.date() == date(2026, 4, 3)
