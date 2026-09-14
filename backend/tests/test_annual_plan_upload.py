"""Uploading a ministry's directory, and re-uploading the corrected sheet. NEEDS POSTGRES.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Skips itself when ``DATABASE_URL`` is not local — ``from conftest import needs_db``.

══ WHAT THIS FILE IS ACTUALLY ABOUT ══════════════════════════════════════════════════════════════

The correction arrives as the WHOLE SHEET again, because that is how a spreadsheet is corrected. So
every test here is a statement about a SECOND upload, and the three that matter most are the ones
whose failure is invisible:

* ``test_uploading_the_same_sheet_twice_writes_nothing_the_second_time`` reads ``updatedAt``, not a
  count. A refactor that calls ``update`` on every row instead of only the differing ones keeps every
  count in the report correct while ``revision`` climbs on every upload and the change list fills
  with no-op rows.
* ``test_correcting_a_promoted_row_updates_the_plan_and_not_the_workshop`` is the most important
  test in this file. An upload that "keeps them in step" silently rewrites a fortnight of somebody's
  fieldwork header from a planning document.
* ``test_a_failure_after_the_creates_leaves_nothing_written`` forces the failure at the SECOND
  statement. Forcing it at ``create_many`` — one statement, atomic on its own — passes with the
  transaction deleted, which is the one thing a transaction test must not do.
"""

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

pytestmark = [needs_db, pytest.mark.anyio]

HEADINGS = [label for _key, label in _PRO_FORMA_COLUMNS]
PASSWORD = "annual-plan-ministry-admin-password"
YEAR = 2026


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """ONE MINISTRY ADMIN, AT THE FLOOR AND NOT ABOVE IT.

    ``MINISTRY_ADMIN`` rather than ADMIN deliberately: an admin would pass this gate for the wrong
    reason (a wider rank), and every assertion below would then be untested against the one tier
    this feature was built for.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"annual-plan-admin-{stamp}@example.org"

    await db.connect()
    try:
        admin = await db.user.create(
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
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "admin": admin, "stamp": stamp}


@pytest.fixture
async def year(world) -> int:
    """A PLAN YEAR OF THIS TEST'S OWN, and every row in the table for it removed first.

    The natural key is ``(planYear, workshopNoKey)``, so two tests sharing a year share rows — and
    a test that asserts "nothing was written" would be asserting about somebody else's fixture.
    Each test gets a year nobody else uses.
    """
    world["year_counter"] = world.get("year_counter", 0) + 1
    chosen = 2100 - world["year_counter"]
    await db.connect()
    try:
        await db.annualplanentry.delete_many(where={"planYear": chosen})
    finally:
        await db.disconnect()
    return chosen


def _excel_datetime(year: int, month: int, day: int) -> datetime:
    """A NAIVE ``datetime``, which is what an Excel date cell IS and the only thing openpyxl writes.

    openpyxl raises ``ValueError: Excel does not support timezones in datetimes`` for a tz-aware
    value, and a workshop planned for 12 March is planned for 12 March in Bhuj and in Delhi alike.
    Said once, here, rather than as a bare ``noqa`` at every fixture that needs a date cell.
    """
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


def _upload(world, data: bytes, *, plan_year: int | None = None, withdraw: bool | None = None):
    form: dict[str, str] = {}
    if plan_year is not None:
        form["planYear"] = str(plan_year)
    if withdraw:
        form["withdrawAbsent"] = "true"
    return world["client"].post(
        "/api/annual-plan/upload",
        files={"file": ("plan.xlsx", data, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
        data=form,
        headers=_headers(world),
    )


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


async def _rows(plan_year: int) -> dict[str, Any]:
    await db.connect()
    try:
        found = await db.annualplanentry.find_many(where={"planYear": plan_year})
        return {row.workshopNoKey: row for row in found}
    finally:
        await db.disconnect()


# --------------------------------------------------------------------------------------
# 1. The first upload, and the second
# --------------------------------------------------------------------------------------


async def test_a_first_upload_creates_every_row_and_reports_the_count(world, year) -> None:
    """Three hundred rows in one ``create_many`` — one statement, whatever the size of the sheet."""
    prefix = f"DPW/{year}"
    response = _upload(world, _book(_three_hundred(prefix)), plan_year=year)
    assert response.status_code == 201, response.text
    report = response.json()
    assert report["rowsRead"] == 300
    assert report["created"] == 300
    assert report["updated"] == 0
    assert report["unchanged"] == 0
    assert report["planYearLabel"] == annual_plan.plan_year_label(year)
    assert len(await _rows(year)) == 300


async def test_uploading_the_same_sheet_twice_writes_nothing_the_second_time(world, year) -> None:
    """``updatedAt`` AND ``revision`` BYTE-IDENTICAL, which is the assertion a count cannot make.

    This is the property that makes "re-upload the corrected sheet" safe to do casually, and it is
    the one a refactor loses without turning anything red: calling ``update`` on every row instead of
    only the differing ones keeps every count correct while ``revision`` climbs and the change list
    fills with no-op rows.
    """
    prefix = f"DPW/{year}"
    sheet = _book(_three_hundred(prefix))
    assert _upload(world, sheet, plan_year=year).status_code == 201
    before = {key: (row.updatedAt, row.revision) for key, row in (await _rows(year)).items()}

    report = _upload(world, sheet, plan_year=year).json()
    assert report["unchanged"] == 300
    assert report["updated"] == 0
    assert report["created"] == 0

    after = {key: (row.updatedAt, row.revision) for key, row in (await _rows(year)).items()}
    assert after == before


async def test_a_corrected_sheet_updates_only_the_rows_that_changed(world, year) -> None:
    """One venue moved. One row updated, one revision incremented, 299 untouched."""
    prefix = f"DPW/{year}"
    rows = _three_hundred(prefix)
    assert _upload(world, _book(rows), plan_year=year).status_code == 201

    venue_index = [key for key, _label in _PRO_FORMA_COLUMNS].index("venue")
    rows[16][venue_index] = "DIC Hall, Bhuj"
    report = _upload(world, _book(rows), plan_year=year).json()

    assert report["updated"] == 1
    assert report["unchanged"] == 299
    stored = await _rows(year)
    assert stored[f"{prefix}/017".upper()].revision == 2
    assert all(row.revision == 1 for key, row in stored.items() if not key.endswith("/017"))


async def test_a_re_sorted_sheet_that_changes_nothing_writes_nothing(world, year) -> None:
    """AND EVERY ``sheetRow`` STILL HOLDS ITS OLD VALUE — the drift the schema declares, asserted.

    The no-write is the whole of idempotency, and its visible cost is that ``sheetRow`` goes stale
    after a re-sort. A well-meaning "fix" that keeps ``sheetRow`` fresh writes three hundred rows for
    a sheet that changed nothing, and has to fail here rather than land quietly.
    """
    prefix = f"DPW/{year}"
    rows = _three_hundred(prefix)
    assert _upload(world, _book(rows), plan_year=year).status_code == 201
    before = {key: (row.updatedAt, row.sheetRow) for key, row in (await _rows(year)).items()}

    report = _upload(world, _book(list(reversed(rows))), plan_year=year).json()
    assert report["unchanged"] == 300
    assert report["updated"] == 0

    after = {key: (row.updatedAt, row.sheetRow) for key, row in (await _rows(year)).items()}
    assert after == before


async def test_a_change_is_reported_field_by_field_with_before_and_after(world, year) -> None:
    """The change list is the feature. A count tells an administrator nothing they can act on."""
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


async def test_a_re_spelled_workshop_number_updates_the_row_it_already_wrote(world, year) -> None:
    """"dpw/2026/017" and "DPW/2026/017" are ONE workshop, and the re-spelling is itself a change.

    The key is identity and the spelling is data. Without the fold, a corrected sheet re-typed in
    lower case creates three hundred rows beside the three hundred that are already there — and
    nothing on any screen distinguishes the two sets.
    """
    prefix = f"DPW/{year}"
    assert _upload(world, _book([_row(workshopNo=f"{prefix}/017")]), plan_year=year).status_code == 201
    report = _upload(
        world, _book([_row(workshopNo=f"{prefix}/017".lower())]), plan_year=year
    ).json()

    stored = await _rows(year)
    assert len(stored) == 1
    row = next(iter(stored.values()))
    assert row.workshopNo == f"{prefix}/017".lower()
    assert row.revision == 2
    assert report["updated"] == 1
    assert report["created"] == 0


# --------------------------------------------------------------------------------------
# 2. Rows the later sheet does not mention
# --------------------------------------------------------------------------------------


async def test_a_row_missing_from_a_later_sheet_is_left_alone_by_default(world, year) -> None:
    """THE DEFAULT IS THE SAFE ONE. A partial correction sheet of twelve rows must not withdraw the
    other 288, and that has to be true when nobody thought about it."""
    prefix = f"DPW/{year}"
    first = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
    assert _upload(world, _book(first), plan_year=year).status_code == 201

    report = _upload(world, _book(first[:1]), plan_year=year).json()
    assert report["absent"] == 1
    assert report["withdrawn"] == 0
    assert report["withdrawAbsentRequested"] is False
    stored = await _rows(year)
    assert stored[f"{prefix}/002".upper()].withdrawnAt is None


async def test_a_row_missing_from_a_later_sheet_is_withdrawn_only_when_asked(world, year) -> None:
    """A stamp and who made it. NOTHING IS DELETED — the row and its history stay."""
    prefix = f"DPW/{year}"
    first = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
    assert _upload(world, _book(first), plan_year=year).status_code == 201

    report = _upload(world, _book(first[:1]), plan_year=year, withdraw=True).json()
    assert report["withdrawn"] == 1
    stored = await _rows(year)
    gone = stored[f"{prefix}/002".upper()]
    assert gone.withdrawnAt is not None
    assert gone.withdrawnById == world["admin"].id
    assert len(stored) == 2, "withdrawn is a stamp, never a delete"


async def test_a_filtered_export_cannot_be_used_to_withdraw_the_rows_its_filter_hid(
    world, year
) -> None:
    """**THE EXPORT AND THE WITHDRAW BOX ARE SAFE APART AND WERE RUINOUS TOGETHER.**

    ``GET /annual-plan/export.xlsx`` applies the LIST's filters. ``apply_parsed_plan`` compares an
    upload against the WHOLE year — its ``find_many`` is ``where={"planYear": …}`` and it takes no
    filter argument — so every row a filtered sheet does not name is "absent", and with the box
    ticked absent means ``withdrawnAt`` stamped by one ``update_many``. Filter the year to one row,
    press "Export this list", correct it, re-upload with the box ticked, and every other workshop in
    the year leaves the Planned standing with a ``withdrawnById`` recording a decision nobody took.

    The export route's own docstring named this hazard and closed it for PAGING while leaving the
    filters producing exactly that shape — under ``annual-plan-2026-27.xlsx``, a filename that reads
    as the whole year. The workbook is the only place the fact survives the round trip: the upload
    sees bytes and a form field and never the query string the download was taken under. So the
    filtered export writes the filter onto its Details sheet and this refusal reads it back.

    THE SAME FILE WITHOUT THE BOX IS STILL ACCEPTED, which is the second assertion and the more
    important one: a partial correction is what a filtered export is FOR, and a guard that refused
    the upload outright would have taken the feature away instead of making it safe.

    UNVERIFIED LOCALLY: the generated Prisma client on this machine predates today's models and
    ``prisma generate`` does not run here, so this first executes in CI.
    """
    prefix = f"DPW/{year}"
    whole_year = [_row(workshopNo=f"{prefix}/{n:03d}", state="Gujarat") for n in (1, 2, 3)]
    assert _upload(world, _book(whole_year), plan_year=year).status_code == 201

    exported = world["client"].get(
        f"/api/annual-plan/export.xlsx?planYear={year}&search={prefix}/001",
        headers=_headers(world),
    )
    assert exported.status_code == 200, exported.text

    refused = _upload(world, exported.content, plan_year=year, withdraw=True)
    assert refused.status_code == 422, refused.text
    detail = refused.json()["detail"]
    assert "filtered view" in detail.lower()
    assert f"{prefix}/001" in detail, "the refusal must name the filter the sheet declares"

    stored = await _rows(year)
    assert [row.withdrawnAt for row in stored.values()] == [None, None, None], (
        "the filtered sheet withdrew the rows its own filter hid"
    )

    # AND THE SAME BYTES, UNTICKED, STILL CORRECT THE ROWS THEY NAME.
    accepted = _upload(world, exported.content, plan_year=year)
    assert accepted.status_code == 201, accepted.text
    assert accepted.json()["withdrawn"] == 0
    assert len(await _rows(year)) == 3


async def test_the_whole_year_export_is_still_allowed_to_withdraw(world, year) -> None:
    """The other half: an UNFILTERED export declares no filter and the box goes on working.

    ``standing="all"`` is what the screen loads with, so a marker written on every export would make
    the refusal above fire on every file this product has ever handed out — the feature would be
    dead rather than guarded, and within a week somebody would delete the guard rather than the
    cause. This is the test that goes red on that.

    UNVERIFIED LOCALLY — see the test above.
    """
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

    # Drop the second row from the sheet the server just handed us, the way an administrator would
    # in Excel, and ask for the withdrawal.
    book = load_workbook(BytesIO(exported.content))
    book["Annual plan"].delete_rows(3)
    buffer = BytesIO()
    book.save(buffer)

    report = _upload(world, buffer.getvalue(), plan_year=year, withdraw=True)
    assert report.status_code == 201, report.text
    assert report.json()["withdrawn"] == 1


async def test_a_withdrawn_row_that_reappears_is_reinstated_and_reported(world, year) -> None:
    """And it is counted in ``updated`` as well as in ``reinstated`` — a reinstatement IS a write."""
    prefix = f"DPW/{year}"
    both = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
    assert _upload(world, _book(both), plan_year=year).status_code == 201
    assert _upload(world, _book(both[:1]), plan_year=year, withdraw=True).status_code == 201

    report = _upload(world, _book(both), plan_year=year).json()
    assert report["reinstated"] == 1
    assert report["updated"] >= 1
    stored = await _rows(year)
    assert stored[f"{prefix}/002".upper()].withdrawnAt is None
    assert stored[f"{prefix}/002".upper()].withdrawnById is None


# --------------------------------------------------------------------------------------
# 3. The year
# --------------------------------------------------------------------------------------


async def test_a_sheet_naming_one_year_uploaded_as_another_is_a_409_naming_both(world, year) -> None:
    """NOTHING IS WRITTEN. Filing three hundred rows under a year the document says it is not for is
    the one mistake a re-upload cannot repair: the corrected sheet lands in the right year and the
    three hundred wrong rows stay where they are."""
    prefix = f"DPW/{year}"
    response = _upload(
        world, _book([_row(workshopNo=f"{prefix}/001")], plan_year=year - 1), plan_year=year
    )
    assert response.status_code == 409
    assert annual_plan.plan_year_label(year) in response.json()["detail"]
    assert annual_plan.plan_year_label(year - 1) in response.json()["detail"]
    assert await _rows(year) == {}
    assert await _rows(year - 1) == {}


async def test_a_sheet_that_names_no_year_and_no_year_chosen_is_a_422(world, year) -> None:
    """A directory with no year is a directory that cannot be corrected later."""
    response = _upload(world, _book([_row(workshopNo=f"DPW/{year}/001")]))
    assert response.status_code == 422
    assert "which year" in response.json()["detail"]
    assert await _rows(year) == {}


# --------------------------------------------------------------------------------------
# 4. The transaction
# --------------------------------------------------------------------------------------


async def test_a_failure_after_the_creates_leaves_nothing_written(world, year, monkeypatch) -> None:
    """THE TRANSACTION TEST, forced at the SECOND statement rather than at the first.

    Two rows already exist; the sheet creates three more AND changes both existing ones. The failure
    is provoked on the second per-row update, which is only reachable after the ``create_many`` has
    landed. Assert: no created row survived, and both existing rows' ``updatedAt`` and ``revision``
    are byte-identical to before.

    Forcing the failure at ``create_many`` — a single statement, atomic on its own — would pass with
    the transaction deleted, which is the one thing a transaction test must not do.
    """
    prefix = f"DPW/{year}"
    seed = [_row(workshopNo=f"{prefix}/001", venue="A"), _row(workshopNo=f"{prefix}/002", venue="B")]
    assert _upload(world, _book(seed), plan_year=year).status_code == 201
    before = {key: (row.updatedAt, row.revision) for key, row in (await _rows(year)).items()}

    calls = {"n": 0}
    original = annual_plan._write_entry_update

    async def _explode(tx, entry_id, data):
        calls["n"] += 1
        if calls["n"] == 2:
            raise RuntimeError("provoked after the creates landed")
        return await original(tx, entry_id, data)

    monkeypatch.setattr(annual_plan, "_write_entry_update", _explode)

    bigger = [
        _row(workshopNo=f"{prefix}/001", venue="A2"),
        _row(workshopNo=f"{prefix}/002", venue="B2"),
        _row(workshopNo=f"{prefix}/003"),
        _row(workshopNo=f"{prefix}/004"),
        _row(workshopNo=f"{prefix}/005"),
    ]
    with pytest.raises(RuntimeError):
        _upload(world, _book(bigger), plan_year=year)

    stored = await _rows(year)
    assert len(stored) == 2, "no created row may survive the rollback"
    assert {key: (row.updatedAt, row.revision) for key, row in stored.items()} == before


# --------------------------------------------------------------------------------------
# 5. The report
# --------------------------------------------------------------------------------------


async def test_the_report_names_every_count_the_screen_draws(world, year) -> None:
    """COUNTS ARE HELD AS DATA ON THE SCREEN, so a count added to the wire cannot be left off it.

    This is the server half of that contract: the eleven keys the panel iterates must all be here,
    on every response, whatever happened.
    """
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


async def test_the_counts_add_up_to_the_rows_read(world, year) -> None:
    """ARITHMETIC NOBODY WAS PINNING. Every row read is created, updated or unchanged — exactly one
    of the three — and the absent family partitions the same way."""
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


async def test_the_change_list_says_when_it_was_truncated(world, year) -> None:
    """A LIST THAT SILENTLY STOPS IS A LIST THAT LIES. The cap is about legibility; the STATEMENT
    that it was reached is about honesty."""
    cap = annual_plan.MAX_REPORTED_CHANGES
    prefix = f"DPW/{year}"
    rows = [_row(workshopNo=f"{prefix}/{n:04d}", venue=f"V{n}") for n in range(cap + 5)]
    assert _upload(world, _book(rows), plan_year=year).status_code == 201

    changed = [_row(workshopNo=f"{prefix}/{n:04d}", venue=f"W{n}") for n in range(cap + 5)]
    report = _upload(world, _book(changed), plan_year=year).json()
    assert report["changesTruncated"] is True
    assert len(report["changes"]) == cap


async def test_the_ceiling_is_the_parsers_and_the_write_never_sees_more(world, year) -> None:
    """``MAX_PLAN_ROWS`` bounds the transaction as well as the read: one ``create_many`` plus at most
    ``MAX_PLAN_ROWS`` updates plus one ``update_many``, inside a 60-second transaction."""
    prefix = f"DPW/{year}"
    rows = [_row(workshopNo=f"{prefix}/{n:04d}") for n in range(MAX_PLAN_ROWS + 3)]
    report = _upload(world, _book(rows), plan_year=year).json()
    assert report["rowsRead"] == MAX_PLAN_ROWS
    assert len(await _rows(year)) == MAX_PLAN_ROWS
    assert any(p["severity"] == "error" for p in report["problems"])


# --------------------------------------------------------------------------------------
# 6. The door
# --------------------------------------------------------------------------------------


async def test_a_non_workbook_is_refused_before_a_byte_of_it_is_read(world, year) -> None:
    """The extension gate is the cheapest of the three refusals and answers first."""
    response = world["client"].post(
        "/api/annual-plan/upload",
        files={"file": ("plan.docx", b"PK\x03\x04nonsense", "application/octet-stream")},
        data={"planYear": str(year)},
        headers=_headers(world),
    )
    assert response.status_code == 415
    assert "Excel workbook" in response.json()["detail"]


async def test_a_designer_may_not_upload_the_ministrys_plan(world, year) -> None:
    """The gate, exercised through the door rather than through the predicate."""
    await db.connect()
    try:
        designer = await db.user.create(
            data={
                "email": f"plan-designer-{uuid.uuid4().hex[:8]}@example.org",
                "name": "A designer",
                "role": "DESIGNER",
                "passwordHash": hash_password(PASSWORD),
            }
        )
    finally:
        await db.disconnect()
    response = world["client"].post(
        "/api/annual-plan/upload",
        files={"file": ("plan.xlsx", _book([_row(workshopNo="DPW/1/1")]), "application/octet-stream")},
        data={"planYear": str(year)},
        headers={"Authorization": f"Bearer {create_access_token(subject=designer.id)}"},
    )
    assert response.status_code == 403
    assert "ministry administrator" in response.json()["detail"].lower()


async def test_a_date_typed_as_text_is_stored_as_the_day_it_reads(world, year) -> None:
    """The parser's day-first rule, all the way through to the column."""
    prefix = f"DPW/{year}"
    assert _upload(
        world,
        _book([_row(workshopNo=f"{prefix}/001", plannedStartDate="03/04/2026")]),
        plan_year=year,
    ).status_code == 201
    stored = next(iter((await _rows(year)).values()))
    assert stored.plannedStartDate.date() == date(2026, 4, 3)
