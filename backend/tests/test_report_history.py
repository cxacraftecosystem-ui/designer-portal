"""The report history endpoint: every file ever generated, and the timestamps a diff is built on.

A report submitted to a ministry is revised three or four times, and nothing in the system could
answer a reviewer's "did you update the cost sheet before you resubmitted?". Most of what that
question needs was already being recorded — ``DwReportExport`` carries the checksum, the size, the
page count, the template, the registry version and the timestamp for every file the SERVER renders.

(What the PHONE contributes is thinner than the column list suggests, and the fixture below reflects
that rather than an idealised row: ``ReportScreen.kt`` sends only the format, template, file name,
timestamp and warnings — no checksum, no size, no page count — and sends it inside a ``runCatching``
that swallows a failure, so an export made with genuinely no network is never recorded at all. Both
are gaps on that surface, not in this endpoint, and the screen says "No checksum recorded" rather
than implying a file it cannot identify.)

Two facts this endpoint adds, neither of which was reachable by any client:

  WHO generated a file. ``GET /{id}/exports`` returns ten fields and ``generatedById`` is not one of
  them, so "who resubmitted this" had no answer even though the column has always been populated.

  WHEN each stage row was last written. ``entry_rows`` returns the rows without their timestamps and
  with ``deletedAt: None`` in the filter, so a client could see the data as it stands and nothing at
  all about when it got that way — and a row DELETED between two exports, which is precisely the
  change a diff must not miss, was invisible by construction.

This endpoint is read-only and additive: it answers with facts already stored and writes nothing.
The last test in this file is a tripwire that keeps it that way, because an export row that can be
rewritten is worse than no record — the checksum is what makes it evidence.

NOTHING HERE TOUCHES A DATABASE. The real router runs with ``db`` replaced by delegates that answer
the reads the route makes — the workshop, its viewer grants, its exports and its stage rows — and
refuse every write.
"""

import asyncio
import sys
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.router import api_router
from app.api.routes import design_workshops as route_module
from app.core import deps

WORKSHOP_ID = "dw-history-1"
OWNER_ID = "designer-1"


def _at(day: int, hour: int = 9) -> datetime:
    return datetime(2026, 3, day, hour, 0, 0, tzinfo=UTC)


# --------------------------------------------------------------------------------------------
# The fixture: one workshop, four exports, a handful of stage rows.
# --------------------------------------------------------------------------------------------


def _workshop(**overrides: Any) -> SimpleNamespace:
    row: dict[str, Any] = {
        "id": WORKSHOP_ID,
        "createdById": OWNER_ID,
        "deletedAt": None,
        "updatedAt": _at(12, 16),
    }
    row.update(overrides)
    return SimpleNamespace(**row)


def _export(
    id: str,
    generated_at: datetime,
    *,
    on_device: bool = False,
    checksum: str = "a" * 64,
    by: SimpleNamespace | None = None,
    generated_by_id: str | None = OWNER_ID,
) -> SimpleNamespace:
    return SimpleNamespace(
        id=id,
        format="DOCX",
        templateId="dch-standard",
        fileName=f"{id}.docx",
        fileSizeBytes=2_400_000,
        pageCount=26,
        checksumSha256=checksum,
        generatedOnDevice=on_device,
        schemaVersion="reg-v1",
        warnings=None,
        generatedAt=generated_at,
        generatedById=generated_by_id,
        generatedBy=by,
    )


def _entry(
    id: str,
    stage_key: str,
    entity_key: str,
    *,
    created: datetime,
    updated: datetime,
    deleted: datetime | None = None,
    data: dict[str, Any] | None = None,
) -> SimpleNamespace:
    # Real registry keys, not invented ones: `workshop_completeness` scores through the registry,
    # so a made-up entity would be silently ignored and the completeness assertions below would
    # pass against a payload that never scored anything.
    return SimpleNamespace(
        id=id,
        stageKey=stage_key,
        entityKey=entity_key,
        ordinal=0,
        data=data or {},
        createdAt=created,
        updatedAt=updated,
        deletedAt=deleted,
    )


DESIGNER = SimpleNamespace(name="Meera Joshi")
FIELD_OFFICER = SimpleNamespace(name="Anil Kumar")

EXPORTS = [
    # Deliberately NOT in generated order — the route must sort, and a fixture already sorted
    # would let a route that forgot to pass its way through.
    _export("exp-2", _at(6), by=DESIGNER),
    _export("exp-4", _at(12), on_device=True, checksum="d" * 64, by=FIELD_OFFICER),
    _export("exp-1", _at(2), by=DESIGNER),
    # The account that generated it has since been deleted: `generatedBy` is SetNull, so both the
    # relation and the id are gone and the honest answer is that nobody is named.
    _export("exp-3", _at(9), checksum="c" * 64, by=None, generated_by_id=None),
]

PROCESS_STAGE = "TRADITIONAL_PROCESS_BASELINE"
SETUP_STAGE = "WORKSHOP_SETUP"

ENTRIES = [
    _entry("row-step-a", PROCESS_STAGE, "processStep", created=_at(1), updated=_at(11),
           data={"name": "Scouring"}),
    _entry("row-step-b", PROCESS_STAGE, "processStep", created=_at(1), updated=_at(1),
           data={"name": "Dabu printing"}),
    _entry("row-setup", SETUP_STAGE, "workshopSetup", created=_at(1), updated=_at(1),
           data={"workshopTitle": "Bagru revival"}),
    # Deleted between export 2 and export 4. `entry_rows` filters this row out entirely, which is
    # why the diff cannot be built on it: a removed row is a change.
    _entry("row-step-gone", PROCESS_STAGE, "processStep", created=_at(1), updated=_at(7),
           deleted=_at(7), data={"name": "Struck out"}),
]


class _Reads:
    """A delegate that answers `find_many` the way Prisma does — and refuses every write.

    It honours `order` and `take` rather than echoing the fixture back, so "newest first" and the
    caps are assertions about the route rather than about the order this file happens to list its
    rows in.
    """

    def __init__(self, rows: list[SimpleNamespace]) -> None:
        self.rows = rows
        self.queries: list[dict[str, Any]] = []

    async def find_many(
        self,
        where: dict[str, Any] | None = None,
        order: dict[str, str] | None = None,
        take: int | None = None,
        include: dict[str, Any] | None = None,
    ) -> list[SimpleNamespace]:
        self.queries.append({"where": where, "order": order, "take": take})
        rows = list(self.rows)
        if order:
            (key, direction), = order.items()
            rows.sort(key=lambda r: getattr(r, key), reverse=direction == "desc")
        return rows[:take] if take else rows

    async def create(self, data: dict[str, Any]) -> SimpleNamespace:
        raise AssertionError("the history endpoint must never write an export row")

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> SimpleNamespace:
        raise AssertionError("the history endpoint must never rewrite an export row")

    async def delete(self, where: dict[str, Any]) -> SimpleNamespace:
        raise AssertionError("the history endpoint must never delete an export row")


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


@pytest.fixture
def history(monkeypatch: pytest.MonkeyPatch):
    """The real route, wired to read-only delegates, called as the workshop's own designer."""
    exports = _Reads(EXPORTS)
    entries = _Reads(ENTRIES)
    workshops = SimpleNamespace(
        find_unique=_answer_with(_workshop()),
        update=_refuse("the history endpoint must never write the workshop"),
    )

    fake_db = SimpleNamespace(
        designworkshop=workshops,
        dwreportexport=exports,
        dwstageentry=entries,
        # `load_workshop_or_404` admits a third party: anybody holding a `DesignWorkshopViewer`
        # row. This fixture grants none, so the stranger below is a genuine stranger — and the
        # endpoint inherits the grant behaviour for free precisely because it reuses that helper
        # rather than restating who may read a workshop.
        designworkshopviewer=SimpleNamespace(find_unique=_no_grant),
    )
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake_db)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", fake_db)

    _CURRENT["user"] = SimpleNamespace(
        id=OWNER_ID, email="designer@example.org", name="Meera Joshi", role="DESIGNER",
        canDownloadDataset=False, canReview=False,
    )

    def get(workshop_id: str = WORKSHOP_ID) -> httpx.Response:
        async def run() -> httpx.Response:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://history.test") as client:
                return await client.get(f"/api/design-workshops/{workshop_id}/report-history")

        return asyncio.run(run())

    yield SimpleNamespace(get=get, exports=exports, entries=entries)
    _CURRENT["user"] = None


def _answer_with(row: Any):
    async def find_unique(where: dict[str, Any], include: dict[str, Any] | None = None) -> Any:
        return row if where.get("id") == WORKSHOP_ID else None

    return find_unique


async def _no_grant(where: dict[str, Any], include: dict[str, Any] | None = None) -> None:
    """Nobody has been let into this workshop besides its creator."""


def _refuse(message: str):
    async def refuse(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError(message)

    return refuse


# --------------------------------------------------------------------------------------------
# The history
# --------------------------------------------------------------------------------------------


def test_every_export_comes_back_newest_first_with_the_facts_that_identify_the_file(history) -> None:
    """The four facts a reviewer holding a printed report needs: when, which template, who, and the
    checksum — which is the only one of them that proves the file in their hand is this row."""
    response = history.get()

    assert response.status_code == 200, response.text
    body = response.json()
    assert [e["id"] for e in body["exports"]] == ["exp-4", "exp-3", "exp-2", "exp-1"]

    newest = body["exports"][0]
    assert newest["checksumSha256"] == "d" * 64
    assert newest["pageCount"] == 26
    assert newest["fileSizeBytes"] == 2_400_000
    assert newest["templateId"] == "dch-standard"
    assert newest["schemaVersion"] == "reg-v1"
    # A phone produced this one with no network. An on-device export exists on exactly one device
    # until somebody copies it off, so the distinction is a fact about the archive, not trivia.
    assert newest["generatedOnDevice"] is True
    assert newest["generatedByName"] == "Anil Kumar"


def test_an_export_whose_author_has_been_deleted_names_nobody_rather_than_guessing(history) -> None:
    """`generatedBy` is SetNull. The honest answer is that the account is gone — not the workshop's
    owner, who is the tempting default and would put a name against a file they never made."""
    body = history.get().json()
    orphan = next(e for e in body["exports"] if e["id"] == "exp-3")

    assert orphan["generatedById"] is None
    assert orphan["generatedByName"] is None


# --------------------------------------------------------------------------------------------
# The timeline the diff is built on
# --------------------------------------------------------------------------------------------


def test_the_timeline_carries_the_timestamps_no_other_endpoint_exposes(history) -> None:
    """`GET /{id}` returns the data and no timestamps at all, so a client can see what a stage says
    and nothing about when it came to say it. Without `updatedAt` there is no diff."""
    body = history.get().json()
    step = next(e for e in body["entries"] if e["id"] == "row-step-a")

    assert step["stageKey"] == PROCESS_STAGE
    assert step["entityKey"] == "processStep"
    assert step["createdAt"].startswith("2026-03-01")
    assert step["updatedAt"].startswith("2026-03-11")


def test_a_deleted_row_is_in_the_timeline_because_deleting_a_cost_line_is_a_change(history) -> None:
    """THE REASON THIS ENDPOINT EXISTS RATHER THAN A FLAG ON `/stages`. `entry_rows` filters
    `deletedAt: None`, so a cost line removed between generation two and generation four leaves no
    trace in any existing payload — and a diff built on those payloads would report the cost sheet
    as unchanged on exactly the revision where a line was struck out."""
    body = history.get().json()
    dropped = next((e for e in body["entries"] if e["id"] == "row-step-gone"), None)

    assert dropped is not None, "a soft-deleted row must survive into the timeline"
    assert dropped["deletedAt"].startswith("2026-03-07")

    # And the route must not have asked the database to hide it.
    for query in history.entries.queries:
        assert (query["where"] or {}).get("deletedAt") is None


def test_the_current_scores_travel_with_the_history_and_exclude_deleted_rows(history) -> None:
    """WHY THE SCORES ARE HERE AT ALL: without them the screen has to call `GET /{id}` purely to
    reach `completeness`, which returns every field of every stage row plus the transcript
    annexure — hundreds of kilobytes over a metered rural connection, to print a percentage.

    AND WHY THE DELETED ROW IS EXCLUDED: it belongs in the timeline, because its removal is a
    change between two files, and nowhere near a count of what the workshop currently holds. Three
    `processStep` rows exist, one of them struck out, so the count is 2 — if the deleted row leaked
    into the score this reads 3, and the screen would report a row the report does not print.
    """
    body = history.get().json()
    stage = body["completeness"][PROCESS_STAGE]

    assert stage["collectionCounts"]["processStep"] == 2
    assert len([e for e in body["entries"] if e["entityKey"] == "processStep"]) == 3
    # Every stage the registry declares is scored, not only the two this workshop has touched —
    # the client walks the registry to draw the list and a missing key would read as 0%.
    assert len(body["completeness"]) == 22


def test_the_workshop_header_carries_its_own_last_write(history) -> None:
    """The cover page's craft, cluster and dates live on the workshop row, not in any stage entry.
    Without this the diff would call a workshop unchanged when its title had been rewritten."""
    body = history.get().json()

    assert body["workshopUpdatedAt"].startswith("2026-03-12")


def test_the_server_states_its_own_clock(history) -> None:
    """"Edited since the last export" is a comparison between a server-written `updatedAt` and now.
    Read `now` from a field laptop whose clock is a day out and the answer is a day of invented or
    hidden edits, so the server says what time it thinks it is and the client compares against that."""
    body = history.get().json()

    assert body["serverTime"], "the client cannot honestly date 'since' without the server's clock"


def test_a_capped_timeline_says_so_rather_than_stopping_quietly(history, monkeypatch) -> None:
    """A list that silently stops is indistinguishable from a workshop with nothing in it — the
    single most repeated bug class in this repository. Two rows past a cap of two must set the flag."""
    monkeypatch.setattr(route_module, "_HISTORY_ENTRY_LIMIT", 2)
    body = history.get().json()

    assert len(body["entries"]) == 2
    assert body["entriesTruncated"] is True
    # AND THE SCORES GO WITH IT. A percentage computed over the rows that happened to fit is not a
    # slightly-wrong percentage, it is a wrong one that looks exactly like a right one — so it is
    # withheld, and the screen draws nothing for a stage it has no score for.
    assert body["completeness"] == {}


# --------------------------------------------------------------------------------------------
# Refusal
# --------------------------------------------------------------------------------------------


def test_a_stranger_gets_the_same_404_the_workshop_itself_gives(history) -> None:
    """PERMISSIONS ARE PAIRS. The history names who generated every file and when every stage was
    touched — a fuller account of somebody's fieldwork than the workshop page itself — so it is
    gated by the same `load_workshop_or_404` as every other read. 404 and not 403: answering 403
    would confirm the id exists to somebody entitled to know nothing about it.

    The owner's 200 is asserted FIRST and in this same test on purpose. A refusal test that only
    asserts 404 passes just as happily against a route that does not exist, which is the shape of
    test that proves nothing — it would have gone green throughout the writing of this endpoint.
    """
    assert history.get().status_code == 200, "the owner must be served, or the 404 below proves nothing"

    _CURRENT["user"] = SimpleNamespace(
        id="someone-else", email="researcher@example.org", name="Passer By", role="RESEARCHER",
        canDownloadDataset=False, canReview=False,
    )

    response = history.get()

    assert response.status_code == 404, response.text


def test_a_workshop_that_does_not_exist_is_a_404_not_an_empty_history(history) -> None:
    """An empty history is a real state — a workshop nobody has generated a report from yet — so a
    missing workshop must not be served as one. As above, the served case is asserted alongside it
    so the test cannot pass against a route that is simply absent."""
    assert history.get().status_code == 200
    assert history.get("no-such-workshop").status_code == 404


def test_reading_the_history_writes_nothing(history) -> None:
    """NEVER MUTATE AN EXPORT RECORD. The delegates raise on create/update/delete, so this passes
    only while the route is genuinely read-only — an export row whose size or checksum can be
    rewritten later is not evidence of anything."""
    assert history.get().status_code == 200
