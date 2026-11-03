import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import media as media_routes


class Row(SimpleNamespace):
    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


class _Delegate:
    def __init__(self, rows: list[Any], seen: list[tuple[str, Any]], name: str) -> None:
        self._rows = rows
        self._seen = seen
        self._name = name

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self._seen.append((self._name, kwargs.get("where")))
        where = kwargs.get("where") or {}
        rows = list(self._rows)
        ids = (where.get("id") or {}).get("in") if isinstance(where.get("id"), dict) else None
        if ids is not None:
            rows = [r for r in rows if r.id in ids]
        types = (where.get("linkedRecordType") or {}).get("in") if isinstance(
            where.get("linkedRecordType"), dict
        ) else None
        if types is not None:
            rows = [r for r in rows if r.linkedRecordType in types]
        if "OR" in where:
            rows = [r for r in rows if any(_matches(r, cond) for cond in where["OR"])]
        return rows

    async def find_unique(self, **kwargs: Any) -> Any:
        wanted = (kwargs.get("where") or {}).get("id")
        return next((r for r in self._rows if r.id == wanted), None)

    async def update(self, **kwargs: Any) -> Any:
        row = await self.find_unique(where=kwargs.get("where") or {})
        for key, value in (kwargs.get("data") or {}).items():
            setattr(row, key, value)
        return row


def _matches(row: Any, cond: dict[str, Any]) -> bool:
    for key, want in cond.items():
        have = getattr(row, key, None)
        if isinstance(want, dict) and "not" in want:
            if have == want["not"]:
                return False
        elif have != want:
            return False
    return True


class _DB:
    def __init__(self, seen: list[tuple[str, Any]], **tables: list[Any]) -> None:
        for name in (
            "mediafile",
            "process",
            "processstep",
            "designworkshop",
            "artisan",
            "craft",
            "workshop",
            "productdocumentation",
            "tooldocumentation",
            "questionnaireinterview",
        ):
            setattr(self, name, _Delegate(tables.get(name, []), seen, name))


ADMIN = Row(id="user-1", role="ADMIN", email="admin@example.test", name="Admin")

_T0 = datetime(2026, 8, 1, 9, 0, tzinfo=UTC)


def _clip(media_id: str, tag: str | None, target: str | None, *, minutes: int = 0, **extra) -> Row:
    return Row(
        id=media_id,
        originalFilename=f"{media_id}.jpg",
        mediaType="IMAGE",
        linkedRecordType=tag,
        linkedRecordId=target,
        createdAt=_T0 + timedelta(minutes=minutes),
        uploadedById="user-1",
        objectKey=f"media/user-1/{media_id}.jpg",
        **extra,
    )


def _orphans(monkeypatch, **tables: list[Any]) -> tuple[list[dict[str, Any]], list[tuple[str, Any]]]:
    seen: list[tuple[str, Any]] = []
    monkeypatch.setattr(media_routes, "db", _DB(seen, **tables))
    rows = asyncio.run(media_routes.list_orphan_media(current_user=ADMIN))
    return rows, seen



@pytest.mark.parametrize(
    ("tag", "table", "live_id"),
    [
        ("processstep", "processstep", "step-live"),
        ("process", "process", "proc-live"),
        ("designWorkshop", "designworkshop", "dw-live"),
        ("designworkshop", "designworkshop", "dw-live"),
        ("misc", "mediafile", "m-live"),
    ],
)
def test_a_tag_only_orphan_is_listed(monkeypatch, tag, table, live_id):
    dead = _clip("m-dead", tag, "gone-forever")
    tables = {"mediafile": [dead], table: [Row(id=live_id)]}
    if table == "mediafile":
        tables["mediafile"] = [dead, Row(id=live_id)]
    rows, _ = _orphans(monkeypatch, **tables)
    assert [r["id"] for r in rows] == ["m-dead"]


def test_a_tag_only_link_whose_parent_is_alive_is_not_an_orphan(monkeypatch):
    rows, _ = _orphans(
        monkeypatch,
        mediafile=[_clip("m-live", "processstep", "step-live")],
        processstep=[Row(id="step-live")],
    )
    assert rows == []


def test_the_fk_orphans_are_still_listed_alongside_them(monkeypatch):
    rows, _ = _orphans(
        monkeypatch,
        mediafile=[
            _clip("m-fk", "artisan", "artisan-gone", minutes=1),
            _clip("m-tag", "processstep", "step-gone", minutes=2),
        ],
    )
    assert {r["id"] for r in rows} == {"m-fk", "m-tag"}


def test_the_two_halves_come_back_newest_first_as_one_list(monkeypatch):
    rows, _ = _orphans(
        monkeypatch,
        mediafile=[
            _clip("m-oldest-fk", "artisan", "artisan-gone", minutes=0),
            _clip("m-newest-tag", "processstep", "step-gone", minutes=30),
            _clip("m-middle-fk", "craft", "craft-gone", minutes=10),
        ],
    )
    assert [r["id"] for r in rows] == ["m-newest-tag", "m-middle-fk", "m-oldest-fk"]


def test_the_parent_lookup_is_one_query_per_type_not_one_per_row(monkeypatch):
    clips = [_clip(f"m-{n}", "processstep", f"step-{n}", minutes=n) for n in range(25)]
    _, seen = _orphans(monkeypatch, mediafile=clips, processstep=[])
    step_reads = [entry for entry in seen if entry[0] == "processstep"]
    assert len(step_reads) == 1, f"expected one batched read, got {len(step_reads)}"


def test_an_untagged_row_is_never_an_orphan(monkeypatch):
    rows, _ = _orphans(monkeypatch, mediafile=[_clip("m-loose", None, None)])
    assert rows == []



def _relink(monkeypatch, media_id: str, rec_type: str, target_id: str, **tables):
    seen: list[tuple[str, Any]] = []
    monkeypatch.setattr(media_routes, "db", _DB(seen, **tables))
    from app.services import design_workshop_posts as posts

    async def _held_by_no_workshop(*_args: Any, **_kwargs: Any) -> set[str]:
        return set()

    monkeypatch.setattr(posts, "media_design_workshop_ids", _held_by_no_workshop)
    monkeypatch.setattr(posts, "link_filing", _held_by_no_workshop)
    payload = SimpleNamespace(linkedRecordType=rec_type, linkedRecordId=target_id)
    return asyncio.run(
        media_routes.relink_media(media_id=media_id, payload=payload, current_user=ADMIN)
    )


@pytest.mark.parametrize("rec_type", ["process", "processstep"])
def test_a_process_or_step_can_be_relinked(monkeypatch, rec_type):
    dead = _clip("m-dead", rec_type, "gone", workshopId="ws-1")
    tables: dict[str, list[Any]] = {"mediafile": [dead]}
    tables[rec_type] = [Row(id="target-1")]
    out = _relink(monkeypatch, "m-dead", rec_type, "target-1", **tables)
    assert out["linkedRecordType"] == rec_type
    assert out["linkedRecordId"] == "target-1"


def test_relinking_a_step_leaves_the_files_workshop_alone(monkeypatch):
    dead = _clip("m-dead", "processstep", "gone", workshopId="ws-1")
    out = _relink(
        monkeypatch, "m-dead", "processstep", "step-live",
        mediafile=[dead], processstep=[Row(id="step-live")],
    )
    assert out["workshopId"] == "ws-1"
