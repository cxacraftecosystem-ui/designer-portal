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
    _export("exp-2", _at(6), by=DESIGNER),
    _export("exp-4", _at(12), on_device=True, checksum="d" * 64, by=FIELD_OFFICER),
    _export("exp-1", _at(2), by=DESIGNER),
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
    _entry("row-step-gone", PROCESS_STAGE, "processStep", created=_at(1), updated=_at(7),
           deleted=_at(7), data={"name": "Struck out"}),
]


class _Reads:
    def __init__(self, rows: list[SimpleNamespace]) -> None:
        self.rows = rows
        self.queries: list[dict[str, Any]] = []
        self.counts: list[dict[str, Any]] = []

    async def count(self, where: dict[str, Any] | None = None) -> int:
        self.counts.append({"where": where})
        clause = ((where or {}).get("generatedAt") or {}).get("lt")
        if clause is None:
            return len(self.rows)
        return sum(1 for r in self.rows if r.generatedAt < clause)

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
    pass


def _refuse(message: str):
    async def refuse(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError(message)

    return refuse



def test_every_export_comes_back_newest_first_with_the_facts_that_identify_the_file(history) -> None:
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
    assert newest["generatedOnDevice"] is True
    assert newest["generatedByName"] == "Anil Kumar"


def test_the_generation_number_counts_from_the_first_file_ever_and_not_from_the_window(
    history, monkeypatch
) -> None:
    monkeypatch.setattr(route_module, "_HISTORY_EXPORT_LIMIT", 2)
    body = history.get().json()

    assert body["exportsTruncated"] is True
    assert [(e["id"], e["generation"]) for e in body["exports"]] == [("exp-4", 4), ("exp-3", 3)]
    assert history.exports.counts, "the route must count the files below the window"
    assert history.exports.counts[-1]["where"]["generatedAt"]["lt"] == _at(9)


def test_the_generation_numbers_are_one_based_and_oldest_first_when_nothing_is_cut(history) -> None:
    body = history.get().json()

    assert [(e["id"], e["generation"]) for e in body["exports"]] == [
        ("exp-4", 4), ("exp-3", 3), ("exp-2", 2), ("exp-1", 1),
    ]
    assert body["exportsTruncated"] is False

    history.exports.rows = []
    history.exports.counts.clear()
    empty = history.get().json()
    assert empty["exports"] == []
    assert history.exports.counts == [], "an empty history must not cost a count query"


def test_an_export_whose_author_has_been_deleted_names_nobody_rather_than_guessing(history) -> None:
    body = history.get().json()
    orphan = next(e for e in body["exports"] if e["id"] == "exp-3")

    assert orphan["generatedById"] is None
    assert orphan["generatedByName"] is None



def test_the_timeline_carries_the_timestamps_no_other_endpoint_exposes(history) -> None:
    body = history.get().json()
    step = next(e for e in body["entries"] if e["id"] == "row-step-a")

    assert step["stageKey"] == PROCESS_STAGE
    assert step["entityKey"] == "processStep"
    assert step["createdAt"].startswith("2026-03-01")
    assert step["updatedAt"].startswith("2026-03-11")


def test_a_deleted_row_is_in_the_timeline_because_deleting_a_cost_line_is_a_change(history) -> None:
    body = history.get().json()
    dropped = next((e for e in body["entries"] if e["id"] == "row-step-gone"), None)

    assert dropped is not None, "a soft-deleted row must survive into the timeline"
    assert dropped["deletedAt"].startswith("2026-03-07")

    for query in history.entries.queries:
        assert (query["where"] or {}).get("deletedAt") is None


def test_the_current_scores_travel_with_the_history_and_exclude_deleted_rows(history) -> None:
    body = history.get().json()
    stage = body["completeness"][PROCESS_STAGE]

    assert stage["collectionCounts"]["processStep"] == 2
    assert len([e for e in body["entries"] if e["entityKey"] == "processStep"]) == 3
    assert len(body["completeness"]) == 22


def test_the_workshop_header_carries_its_own_last_write(history) -> None:
    body = history.get().json()

    assert body["workshopUpdatedAt"].startswith("2026-03-12")


def test_the_server_states_its_own_clock(history) -> None:
    body = history.get().json()

    assert body["serverTime"], "the client cannot honestly date 'since' without the server's clock"


def test_a_capped_timeline_says_so_rather_than_stopping_quietly(history, monkeypatch) -> None:
    monkeypatch.setattr(route_module, "_HISTORY_ENTRY_LIMIT", 2)
    body = history.get().json()

    assert len(body["entries"]) == 2
    assert body["entriesTruncated"] is True
    assert body["completeness"] == {}



def test_a_stranger_gets_the_same_404_the_workshop_itself_gives(history) -> None:
    assert history.get().status_code == 200, "the owner must be served, or the 404 below proves nothing"

    _CURRENT["user"] = SimpleNamespace(
        id="someone-else", email="researcher@example.org", name="Passer By", role="RESEARCHER",
        canDownloadDataset=False, canReview=False,
    )

    response = history.get()

    assert response.status_code == 404, response.text


def test_a_workshop_that_does_not_exist_is_a_404_not_an_empty_history(history) -> None:
    assert history.get().status_code == 200
    assert history.get("no-such-workshop").status_code == 404


def test_reading_the_history_writes_nothing(history) -> None:
    assert history.get().status_code == 200
