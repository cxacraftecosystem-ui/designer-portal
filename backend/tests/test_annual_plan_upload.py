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

══ HOW THIS FILE IS ARRANGED, AND WHY THE FIXTURE HOLDS EVERY DATABASE CALL ══════════════════════

``db`` is a PROCESS-WIDE Prisma singleton shared with the running application, and a Prisma
connection is BOUND TO THE EVENT LOOP THAT OPENED IT. A ``TestClient`` runs the app in a portal
thread of its own and the app's lifespan connects ``db`` there — so for as long as a client is
alive the singleton belongs to that loop, and a test that reaches for ``db`` from its own loop is
borrowing a connection nothing in its loop owns.

THIS MODULE USED TO DO EXACTLY THAT, TWICE PER TEST. A module-scoped ``world`` held one live client
for the whole module, while a function-scoped ``year`` fixture called ``db.connect()`` and
``delete_many`` from the test's own loop and a ``_rows`` helper did the same for every read-back.
That is 57 ``ERROR at setup`` here — and then three more in ``tests/test_save_stage_resubmission.py``,
a module with a correct fixture and no client of its own, failing with ``RuntimeError: Event loop is
closed`` because a half-opened connection outlived this module.

``tests/test_workshop_join_sync.py`` carries the simple form of the convention that works and
``tests/test_sanction_orders.py`` the longer one; this file is on the longer one. **Every database
call happens inside ONE SYNC fixture, in a private ``asyncio.run`` loop, with no ``TestClient``
alive at the time — and the tests assert over what it recorded.** ``asyncio.run`` opens a loop
nothing else shares and closes it again, so the blind ``connect``/``disconnect`` pair inside it is
correct here precisely where it would be wrong in an async fixture interleaving with an app
lifespan.

Six phases, and the order is the whole design:

1. **seed** — ``asyncio.run``: the ministry admin, the designer the door test needs, and every plan
   year this module hands out, emptied before a single row is written.
2. **act one** — one ``TestClient``: every upload whose RESULT a database read has to observe. The
   ``httpx.Response`` objects are kept whole (status, ``.content``, ``.json()`` all still answer
   after the client is closed), so the assertions below are unchanged from when each test made its
   own request.
3. **observe one** — ``asyncio.run``, the client now shut down and the singleton free: the
   read-backs, including the ``before`` snapshots the second round is asserted against.
4. **act two** — a second ``TestClient``, for the four uploads that HAD to follow a read: three
   idempotency tests whose whole assertion is that a row did not move between a read and a re-read,
   and the transaction test's provoked failure.
5. **observe two** — ``asyncio.run`` again: the matching ``after`` reads.
6. **yield** — a live ``TestClient`` for the tests that need no database read at all. They drive it
   themselves, exactly as ``test_workshop_join_sync`` does: what the convention forbids is a
   DATABASE call while a client is alive, not a request.

The workbooks are built where they are uploaded rather than in ``seed``. They are bytes, not rows,
and the rule the phases exist for is about database calls.

A test whose upload was made in a fixture phase asserts its status code FIRST, so a route that
broke fails the test that owns it rather than erroring the whole module out of the fixture.
"""

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

#: The position of ``venue`` in a pro-forma row, for the one test that edits a cell in place.
_VENUE_INDEX = [key for key, _label in _PRO_FORMA_COLUMNS].index("venue")

#: EVERY TEST'S PLAN YEAR, ALLOCATED HERE AND NOWHERE ELSE.
#:
#: The natural key is ``(planYear, workshopNoKey)``, so two tests sharing a year share rows — and a
#: test that asserts "nothing was written" would be asserting about somebody else's fixture. Each
#: test gets a year nobody else uses; that was a per-test fixture's job and is now ``seed``'s,
#: because the emptying is a database call and a database call may not happen mid-test.
#:
#: Each entry is ``(fixture key, test name)`` in file order. The fixture files a test's recorded
#: answers and rows under the key; the ``year`` fixture looks the year up by the test's name, so a
#: test added without an entry here fails saying so rather than quietly borrowing a neighbour's.
#:
#: THE YEARS COUNT DOWN IN TWOS, which is not decoration.
#: ``test_a_sheet_naming_one_year_uploaded_as_another_is_a_409_naming_both`` asserts that ``year -
#: 1`` — the year the workbook named — is empty too, and under a contiguous allocation that year
#: belonged to the NEXT test in the file and was empty only because that test happens never to write
#: a row. Stepping by two makes the year below each test's own nobody's, by construction.
#:
#: They start below 2090 rather than at 2100 because ``test_annual_plan_promotion`` seeds 2099 and
#: ``test_annual_plan_is_not_a_workshop`` seeds 2098: ``seed`` empties every year it hands out, and
#: emptying another module's year is a cross-module accident waiting on a change of collection
#: order.
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

#: fixture key -> plan year.
_PLAN_YEARS: dict[str, int] = {
    key: _FIRST_PLAN_YEAR - 2 * index for index, (key, _test) in enumerate(_CASES)
}

#: test name -> plan year, the same allocation read the other way round.
_YEAR_BY_TEST: dict[str, int] = {
    test: _FIRST_PLAN_YEAR - 2 * index for index, (_key, test) in enumerate(_CASES)
}


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
    """ONE upload, against whichever client the caller is holding.

    The fixture's act phases pass their own short-lived client; the live tests reach it through
    ``_upload`` below.
    """
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
    """A LIVE upload, for the tests that need no database read afterwards.

    Those tests keep making their own requests — see the module docstring. What the convention
    forbids is a DATABASE call while a client is alive, not a request.
    """
    return _post(
        world["client"], _headers(world), data, plan_year=plan_year, withdraw=withdraw
    )


async def _rows(plan_year: int) -> dict[str, Any]:
    """Every row of one plan year, keyed by ``workshopNoKey``. CALLED ONLY FROM AN OBSERVE PHASE.

    IT DOES NOT OPEN THE CONNECTION, and that is the difference from the version this module used
    to carry. The phase around it owns one ``connect``/``disconnect`` pair for all of its reads, so
    nineteen read-backs cost one engine start rather than nineteen. Called from a test — which is
    what this module used to do — either shape takes the singleton away from the app's loop.
    """
    found = await db.annualplanentry.find_many(where={"planYear": plan_year})
    return {row.workshopNoKey: row for row in found}


@pytest.fixture(scope="module")
def world():
    """ONE MINISTRY ADMIN, AT THE FLOOR AND NOT ABOVE IT — and every database call in this module.

    ``MINISTRY_ADMIN`` rather than ADMIN deliberately: an admin would pass this gate for the wrong
    reason (a wider rank), and every assertion below would then be untested against the one tier
    this feature was built for.

    SYNC, AND THAT IS THE POINT. See the module docstring: the phases below are ordered so that no
    database call ever happens while a ``TestClient`` — and therefore the app's own, differently
    looped, connection — is alive.
    """
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

    # ---------------------------------------------------------------------------------
    # Phase 1 — seed. A private loop, no app, no TestClient.
    # ---------------------------------------------------------------------------------
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
            # The account the door test is refused as. Created here rather than in that test for
            # the reason the whole module is arranged this way, and the test is a pure request.
            facts["designer"] = await db.user.create(
                data={
                    "email": f"plan-designer-{uuid.uuid4().hex[:8]}@example.org",
                    "name": "A designer",
                    "role": "DESIGNER",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            # EVERY YEAR THIS MODULE WILL TOUCH, EMPTIED BEFORE ANYTHING IS UPLOADED — a previous
            # run's rows under the same year would be read back as this run's. The year BELOW each
            # allocation is emptied too: it is nobody's (the allocation steps by two) and it is what
            # the 409 test asserts is still empty afterwards.
            for chosen in years.values():
                await db.annualplanentry.delete_many(where={"planYear": chosen})
                await db.annualplanentry.delete_many(where={"planYear": chosen - 1})
        finally:
            await db.disconnect()

    # ---------------------------------------------------------------------------------
    # Phase 2 — act one. One TestClient, every upload a later read has to observe.
    # ---------------------------------------------------------------------------------
    def act_one(client: Any) -> None:
        headers = _headers(facts)

        def post(data: bytes, *, plan_year: int | None = None, withdraw: bool | None = None):
            return _post(client, headers, data, plan_year=plan_year, withdraw=withdraw)

        # 1. The first upload.
        year = years["first_upload"]
        answers["first_upload"] = post(_book(_three_hundred(f"DPW/{year}")), plan_year=year)

        # 2. The same sheet twice — THE SAME BYTES, kept for the second round.
        year = years["twice"]
        books["twice"] = _book(_three_hundred(f"DPW/{year}"))
        answers["twice_first"] = post(books["twice"], plan_year=year)

        # 3. One venue moved.
        year = years["corrected"]
        corrected = _three_hundred(f"DPW/{year}")
        answers["corrected_first"] = post(_book(corrected), plan_year=year)
        corrected[16][_VENUE_INDEX] = "DIC Hall, Bhuj"
        answers["corrected_second"] = post(_book(corrected), plan_year=year)

        # 4. The re-sort, whose second half has to follow a read.
        year = years["resorted"]
        answers["resorted_first"] = post(_book(_three_hundred(f"DPW/{year}")), plan_year=year)

        # 6. The re-spelled workshop number.
        year = years["respelled"]
        prefix = f"DPW/{year}"
        answers["respelled_first"] = post(_book([_row(workshopNo=f"{prefix}/017")]), plan_year=year)
        answers["respelled_second"] = post(
            _book([_row(workshopNo=f"{prefix}/017".lower())]), plan_year=year
        )

        # 7. A row the later sheet does not mention, nobody having asked for a withdrawal.
        year = years["absent_default"]
        prefix = f"DPW/{year}"
        pair = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
        answers["absent_default_first"] = post(_book(pair), plan_year=year)
        answers["absent_default_second"] = post(_book(pair[:1]), plan_year=year)

        # 8. The same, with the box ticked.
        year = years["withdraw_asked"]
        prefix = f"DPW/{year}"
        pair = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
        answers["withdraw_asked_first"] = post(_book(pair), plan_year=year)
        answers["withdraw_asked_second"] = post(_book(pair[:1]), plan_year=year, withdraw=True)

        # 9. The filtered export, and the refusal. The SAME BYTES are re-uploaded unticked in the
        #    second round, after the read that proves the refusal withdrew nothing.
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

        # 11. Withdrawn, then reappearing.
        year = years["reinstated"]
        prefix = f"DPW/{year}"
        both = [_row(workshopNo=f"{prefix}/001"), _row(workshopNo=f"{prefix}/002")]
        answers["reinstated_first"] = post(_book(both), plan_year=year)
        answers["reinstated_withdraw"] = post(_book(both[:1]), plan_year=year, withdraw=True)
        answers["reinstated_third"] = post(_book(both), plan_year=year)

        # 12. One year in the workbook, another on the form.
        year = years["year_mismatch"]
        answers["year_mismatch"] = post(
            _book([_row(workshopNo=f"DPW/{year}/001")], plan_year=year - 1), plan_year=year
        )

        # 13. No year anywhere.
        year = years["no_year"]
        answers["no_year"] = post(_book([_row(workshopNo=f"DPW/{year}/001")]))

        # 14. The transaction's two existing rows. The provoked failure is in the second round,
        #     after their ``updatedAt`` and ``revision`` have been read.
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

        # 18. The parser's ceiling.
        year = years["ceiling"]
        prefix = f"DPW/{year}"
        answers["ceiling"] = post(
            _book([_row(workshopNo=f"{prefix}/{n:04d}") for n in range(MAX_PLAN_ROWS + 3)]),
            plan_year=year,
        )

        # 21. A date typed as text.
        year = years["date_as_text"]
        answers["date_as_text"] = post(
            _book([_row(workshopNo=f"DPW/{year}/001", plannedStartDate="03/04/2026")]),
            plan_year=year,
        )

    # ---------------------------------------------------------------------------------
    # Phase 3 — observe one. The client is shut down; the singleton is free again.
    # ---------------------------------------------------------------------------------
    async def observe_one() -> None:
        await db.connect()
        try:
            rows["first_upload"] = await _rows(years["first_upload"])
            # The three ``before`` snapshots. Read BEFORE the second round's uploads, which is the
            # whole of what those tests assert: that the rows did not move between this read and
            # the next.
            rows["twice_before"] = await _rows(years["twice"])
            rows["resorted_before"] = await _rows(years["resorted"])
            rows["rollback_before"] = await _rows(years["rollback"])
            rows["corrected"] = await _rows(years["corrected"])
            rows["respelled"] = await _rows(years["respelled"])
            rows["absent_default"] = await _rows(years["absent_default"])
            rows["withdraw_asked"] = await _rows(years["withdraw_asked"])
            # Read between the refusal and the same file's accepted re-upload, because what it has
            # to show is the state the REFUSAL left behind.
            rows["filtered_refused"] = await _rows(years["filtered_export"])
            rows["reinstated"] = await _rows(years["reinstated"])
            rows["year_mismatch"] = await _rows(years["year_mismatch"])
            rows["year_mismatch_named"] = await _rows(years["year_mismatch"] - 1)
            rows["no_year"] = await _rows(years["no_year"])
            rows["ceiling"] = await _rows(years["ceiling"])
            rows["date_as_text"] = await _rows(years["date_as_text"])
        finally:
            await db.disconnect()

    # ---------------------------------------------------------------------------------
    # Phase 4 — act two. A second TestClient, for the uploads that had to follow a read.
    # ---------------------------------------------------------------------------------
    def act_two(client: Any) -> None:
        headers = _headers(facts)

        def post(data: bytes, *, plan_year: int | None = None, withdraw: bool | None = None):
            return _post(client, headers, data, plan_year=plan_year, withdraw=withdraw)

        # 2. The same bytes again.
        answers["twice_second"] = post(books["twice"], plan_year=years["twice"])

        # 4. The same three hundred rows, re-sorted and otherwise identical.
        year = years["resorted"]
        answers["resorted_second"] = post(
            _book(list(reversed(_three_hundred(f"DPW/{year}")))), plan_year=year
        )

        # 9. THE SAME FILE, UNTICKED, which must still be accepted.
        answers["filtered_accepted"] = post(
            answers["filtered_export"].content, plan_year=years["filtered_export"]
        )

        # 14. The provoked failure, at the SECOND per-row update — reachable only after the
        #     ``create_many`` has landed. Patched by hand rather than with ``monkeypatch``, which is
        #     function-scoped and cannot reach a module fixture; restored in a ``finally`` so a
        #     failure here cannot leak an exploding service into another module.
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

    # ---------------------------------------------------------------------------------
    # Phase 5 — observe two. The matching ``after`` reads.
    # ---------------------------------------------------------------------------------
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
    """A PLAN YEAR OF THIS TEST'S OWN, allocated in ``seed`` and looked up here by name.

    The natural key is ``(planYear, workshopNoKey)``, so two tests sharing a year share rows — and
    a test that asserts "nothing was written" would be asserting about somebody else's fixture.
    Each test gets a year nobody else uses. This fixture only reads the allocation: the emptying is
    a database call and it happens in ``seed``, with no client alive.
    """
    chosen = _YEAR_BY_TEST.get(request.node.name)
    if chosen is None:
        raise AssertionError(
            f"{request.node.name} has no entry in _CASES, so no plan year was allocated for it and "
            "no year of its own was emptied. Add it there rather than borrowing a neighbour's."
        )
    return chosen


# --------------------------------------------------------------------------------------
# 1. The first upload, and the second
# --------------------------------------------------------------------------------------


def test_a_first_upload_creates_every_row_and_reports_the_count(world, year) -> None:
    """Three hundred rows in one ``create_many`` — one statement, whatever the size of the sheet."""
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
    """``updatedAt`` AND ``revision`` BYTE-IDENTICAL, which is the assertion a count cannot make.

    This is the property that makes "re-upload the corrected sheet" safe to do casually, and it is
    the one a refactor loses without turning anything red: calling ``update`` on every row instead of
    only the differing ones keeps every count correct while ``revision`` climbs and the change list
    fills with no-op rows.

    The two uploads are the SAME BYTES, and the ``before`` read sits between them — act one, observe
    one, act two — because a read taken after both would prove nothing about the second.
    """
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
    """One venue moved. One row updated, one revision incremented, 299 untouched."""
    prefix = f"DPW/{year}"
    assert world["answers"]["corrected_first"].status_code == 201

    report = world["answers"]["corrected_second"].json()

    assert report["updated"] == 1
    assert report["unchanged"] == 299
    stored = world["rows"]["corrected"]
    assert stored[f"{prefix}/017".upper()].revision == 2
    assert all(row.revision == 1 for key, row in stored.items() if not key.endswith("/017"))


def test_a_re_sorted_sheet_that_changes_nothing_writes_nothing(world) -> None:
    """AND EVERY ``sheetRow`` STILL HOLDS ITS OLD VALUE — the drift the schema declares, asserted.

    The no-write is the whole of idempotency, and its visible cost is that ``sheetRow`` goes stale
    after a re-sort. A well-meaning "fix" that keeps ``sheetRow`` fresh writes three hundred rows for
    a sheet that changed nothing, and has to fail here rather than land quietly.
    """
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


def test_a_re_spelled_workshop_number_updates_the_row_it_already_wrote(world, year) -> None:
    """"dpw/2026/017" and "DPW/2026/017" are ONE workshop, and the re-spelling is itself a change.

    The key is identity and the spelling is data. Without the fold, a corrected sheet re-typed in
    lower case creates three hundred rows beside the three hundred that are already there — and
    nothing on any screen distinguishes the two sets.
    """
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


# --------------------------------------------------------------------------------------
# 2. Rows the later sheet does not mention
# --------------------------------------------------------------------------------------


def test_a_row_missing_from_a_later_sheet_is_left_alone_by_default(world, year) -> None:
    """THE DEFAULT IS THE SAFE ONE. A partial correction sheet of twelve rows must not withdraw the
    other 288, and that has to be true when nobody thought about it."""
    prefix = f"DPW/{year}"
    assert world["answers"]["absent_default_first"].status_code == 201

    report = world["answers"]["absent_default_second"].json()
    assert report["absent"] == 1
    assert report["withdrawn"] == 0
    assert report["withdrawAbsentRequested"] is False
    stored = world["rows"]["absent_default"]
    assert stored[f"{prefix}/002".upper()].withdrawnAt is None


def test_a_row_missing_from_a_later_sheet_is_withdrawn_only_when_asked(world, year) -> None:
    """A stamp and who made it. NOTHING IS DELETED — the row and its history stay."""
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

    # AND THE SAME BYTES, UNTICKED, STILL CORRECT THE ROWS THEY NAME.
    accepted = world["answers"]["filtered_accepted"]
    assert accepted.status_code == 201, accepted.text
    assert accepted.json()["withdrawn"] == 0
    assert len(world["rows"]["filtered_accepted"]) == 3


def test_the_whole_year_export_is_still_allowed_to_withdraw(world, year) -> None:
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


def test_a_withdrawn_row_that_reappears_is_reinstated_and_reported(world, year) -> None:
    """And it is counted in ``updated`` as well as in ``reinstated`` — a reinstatement IS a write."""
    prefix = f"DPW/{year}"
    assert world["answers"]["reinstated_first"].status_code == 201
    assert world["answers"]["reinstated_withdraw"].status_code == 201

    report = world["answers"]["reinstated_third"].json()
    assert report["reinstated"] == 1
    assert report["updated"] >= 1
    stored = world["rows"]["reinstated"]
    assert stored[f"{prefix}/002".upper()].withdrawnAt is None
    assert stored[f"{prefix}/002".upper()].withdrawnById is None


# --------------------------------------------------------------------------------------
# 3. The year
# --------------------------------------------------------------------------------------


def test_a_sheet_naming_one_year_uploaded_as_another_is_a_409_naming_both(world, year) -> None:
    """NOTHING IS WRITTEN. Filing three hundred rows under a year the document says it is not for is
    the one mistake a re-upload cannot repair: the corrected sheet lands in the right year and the
    three hundred wrong rows stay where they are."""
    response = world["answers"]["year_mismatch"]
    assert response.status_code == 409
    assert annual_plan.plan_year_label(year) in response.json()["detail"]
    assert annual_plan.plan_year_label(year - 1) in response.json()["detail"]
    assert world["rows"]["year_mismatch"] == {}
    assert world["rows"]["year_mismatch_named"] == {}


def test_a_sheet_that_names_no_year_and_no_year_chosen_is_a_422(world) -> None:
    """A directory with no year is a directory that cannot be corrected later."""
    response = world["answers"]["no_year"]
    assert response.status_code == 422
    assert "which year" in response.json()["detail"]
    assert world["rows"]["no_year"] == {}


# --------------------------------------------------------------------------------------
# 4. The transaction
# --------------------------------------------------------------------------------------


def test_a_failure_after_the_creates_leaves_nothing_written(world) -> None:
    """THE TRANSACTION TEST, forced at the SECOND statement rather than at the first.

    Two rows already exist; the sheet creates three more AND changes both existing ones. The failure
    is provoked on the second per-row update, which is only reachable after the ``create_many`` has
    landed. Assert: no created row survived, and both existing rows' ``updatedAt`` and ``revision``
    are byte-identical to before.

    Forcing the failure at ``create_many`` — a single statement, atomic on its own — would pass with
    the transaction deleted, which is the one thing a transaction test must not do.

    The provocation is driven from the fixture (act two), because the ``before`` read has to happen
    between the two uploads and a read cannot happen while a client is alive. ``rollback_error`` is
    what ``pytest.raises(RuntimeError)`` used to assert, recorded rather than caught here — the
    assertion is unchanged, only its position moved.

    ⚠ **THIS ASSERTION IS EXPECTED TO FAIL ON CI, FOR A SECOND DEFECT THAT IS NOT THE LOOP RULE,
    AND THE FIX IS ONE LINE.** ``app.main.UnhandledErrorMiddleware`` is installed unconditionally
    (``app.main`` line 867) and CATCHES every unhandled exception below CORS, returning
    ``JSONResponse(500, {"detail": …, "error": type(exc).__name__, "path": …})`` — it re-raises only
    when the response has already started, which it has not here. So the provoked ``RuntimeError``
    never reaches the caller and ``pytest.raises(RuntimeError)`` could not have passed against this
    app in any arrangement of this module. MEASURED in this repository's own venv, with this
    repository's FastAPI, against a toy app carrying a middleware of that exact shape: bare app →
    ``RAISED RuntimeError``; same app with the middleware → ``returned 500 {'error':
    'RuntimeError'}``. This module has never run (it errored at setup for the whole of its life), so
    nothing has ever exercised the line.

    The honest replacement, which asserts the same fact through the stack that is actually there::

        answered = world["answers"]["rollback_second"]
        assert answered.status_code == 500, answered.text
        assert answered.json()["error"] == "RuntimeError", answered.text

    It is left unmade deliberately: this pass was asked to change HOW these tests reach the
    database and not WHAT they check, and the rollback — the two assertions below, which are the
    subject of the test — is unaffected either way.
    """
    assert world["answers"]["rollback_first"].status_code == 201
    before = {
        key: (row.updatedAt, row.revision) for key, row in world["rows"]["rollback_before"].items()
    }

    raised = world["rollback_error"]
    answered = world["answers"].get("rollback_second")
    assert isinstance(raised, RuntimeError), (
        f"the provoked failure did not reach the caller as a RuntimeError ({raised!r}); the upload "
        f"answered {answered.status_code if answered is not None else 'nothing'} instead. If that "
        "is a 500 naming RuntimeError, UnhandledErrorMiddleware turned the exception into a "
        "response and this line wants the three-line replacement in the docstring above — the "
        "rollback itself is asserted below and is not in question."
    )

    stored = world["rows"]["rollback_after"]
    assert len(stored) == 2, "no created row may survive the rollback"
    assert {key: (row.updatedAt, row.revision) for key, row in stored.items()} == before


# --------------------------------------------------------------------------------------
# 5. The report
# --------------------------------------------------------------------------------------


def test_the_report_names_every_count_the_screen_draws(world, year) -> None:
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


def test_the_counts_add_up_to_the_rows_read(world, year) -> None:
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


def test_the_change_list_says_when_it_was_truncated(world, year) -> None:
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


def test_the_ceiling_is_the_parsers_and_the_write_never_sees_more(world) -> None:
    """``MAX_PLAN_ROWS`` bounds the transaction as well as the read: one ``create_many`` plus at most
    ``MAX_PLAN_ROWS`` updates plus one ``update_many``, inside a 60-second transaction."""
    report = world["answers"]["ceiling"].json()
    assert report["rowsRead"] == MAX_PLAN_ROWS
    assert len(world["rows"]["ceiling"]) == MAX_PLAN_ROWS
    assert any(p["severity"] == "error" for p in report["problems"])


# --------------------------------------------------------------------------------------
# 6. The door
# --------------------------------------------------------------------------------------


def test_a_non_workbook_is_refused_before_a_byte_of_it_is_read(world, year) -> None:
    """The extension gate is the cheapest of the three refusals and answers first."""
    response = world["client"].post(
        "/api/annual-plan/upload",
        files={"file": ("plan.docx", b"PK\x03\x04nonsense", "application/octet-stream")},
        data={"planYear": str(year)},
        headers=_headers(world),
    )
    assert response.status_code == 415
    assert "Excel workbook" in response.json()["detail"]


def test_a_designer_may_not_upload_the_ministrys_plan(world, year) -> None:
    """The gate, exercised through the door rather than through the predicate.

    The designer is seeded in the fixture — the row is a precondition, not the subject — and the
    request is made live here, because nothing afterwards has to be read back.
    """
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
    """The parser's day-first rule, all the way through to the column."""
    assert world["answers"]["date_as_text"].status_code == 201
    stored = next(iter(world["rows"]["date_as_text"].values()))
    assert stored.plannedStartDate.date() == date(2026, 4, 3)
