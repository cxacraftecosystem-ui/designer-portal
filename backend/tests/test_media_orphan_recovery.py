"""``GET /media/orphans`` and ``POST /media/{id}/relink`` for TAG-ONLY links.

THE RECOVERY SCREEN REPORTED A CLEAN REPOSITORY AND THE RECOVERY ROUTE ANSWERED 400. Both were
asking a question that cannot be asked of half the links in this table.

``ORPHAN_FK_FIELDS`` phrases "orphan" as *tagged with a type and an id, but the typed foreign key is
NULL* — which works for the seven link types that HAVE a typed column on ``MediaFile`` and is
unanswerable for the ones that do not. A photograph attached to a Process, a ProcessStep, a
DesignWorkshop or another media file carries only ``linkedRecordType``/``linkedRecordId``
(``processes.py``: "Media is linked purely through linkedRecordType/linkedRecordId … so no MediaFile
foreign keys are needed"). There is no ``fk`` to be null, so no condition in ``list_orphan_media``
could ever match one, and ``_relink_delegate`` had no entry for ``process`` or ``processstep``, so an
admin who found the ids by hand got "Unsupported record type for re-linking".

THE TRIGGER IS AN ORDINARY EDIT, NOT A DELETE. The step-sync in ``processes.py`` hard-deletes every
ProcessStep the form did not re-send (``db.processstep.delete_many(where={"id": {"in": plan.removed}})``),
so a researcher who opens a process, removes one duplicated step and saves has just orphaned that
step's photographs. They vanish from the process detail screen, ``/export/dataset`` fetches and
discards them, and the feature built for exactly this reported nothing wrong.

NOTHING HERE TOUCHES A DATABASE. ``media.db`` is replaced with delegates over fixed rows and the
route functions are awaited directly, because what is under test is WHICH QUESTION the route asks —
a pure function of the rows once they are loaded. That keeps it a second-long test, and it lets the
query shape be asserted directly, which is the part a Postgres test would not show:
``_tag_only_orphans`` surveys the tagged media once WITHOUT relations, asks one
``id: {"in": …}`` per tagged type, and re-reads only the orphans with ``INCLUDE``.
"""

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import media as media_routes


class Row(SimpleNamespace):
    """A record row answering ``None`` for any column the test did not set.

    ``display_filename`` and the field registry read columns by plain attribute access, because on a
    real Prisma row they always exist; a bare ``SimpleNamespace`` would raise on the first unset one
    and the test would be about the fixture instead of about the route.
    """

    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


class _Delegate:
    """One Prisma delegate over fixed rows, honouring only the filters these routes actually use."""

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
    """The subset of Prisma's ``where`` grammar ``list_orphan_media``'s FK conditions use."""
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


# --------------------------------------------------------------------------------------
# 1. Listing an orphan whose only link is a tag
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("tag", "table", "live_id"),
    [
        ("processstep", "processstep", "step-live"),
        ("process", "process", "proc-live"),
        # Both spellings of the workshop tag: the clients send camelCase
        # (``dictation_consent.MEDIA_TAG``) and the relink route lower-cases what it stores, so rows
        # written by a previous recovery carry the other one. Matching one and not the other would
        # leave half the orphans invisible, which is the defect all over again.
        ("designWorkshop", "designworkshop", "dw-live"),
        ("designworkshop", "designworkshop", "dw-live"),
        ("misc", "mediafile", "m-live"),
    ],
)
def test_a_tag_only_orphan_is_listed(monkeypatch, tag, table, live_id):
    """THE DEFECT. No ``fk: None`` predicate can match a row that has no FK, so none of these ever
    appeared on the recovery screen however long the parent had been gone."""
    dead = _clip("m-dead", tag, "gone-forever")
    tables = {"mediafile": [dead], table: [Row(id=live_id)]}
    if table == "mediafile":
        tables["mediafile"] = [dead, Row(id=live_id)]
    rows, _ = _orphans(monkeypatch, **tables)
    assert [r["id"] for r in rows] == ["m-dead"]


def test_a_tag_only_link_whose_parent_is_alive_is_not_an_orphan(monkeypatch):
    """The control, and the one a careless fix breaks: listing every tagged row would fill the
    recovery screen with every process photograph in the repository and hide the real orphans."""
    rows, _ = _orphans(
        monkeypatch,
        mediafile=[_clip("m-live", "processstep", "step-live")],
        processstep=[Row(id="step-live")],
    )
    assert rows == []


def test_the_fk_orphans_are_still_listed_alongside_them(monkeypatch):
    """Both questions, not one instead of the other. The FK half is the cheap predicate and must
    survive: an artisan deleted out from under a portrait is still the commonest orphan there is."""
    rows, _ = _orphans(
        monkeypatch,
        mediafile=[
            _clip("m-fk", "artisan", "artisan-gone", minutes=1),
            _clip("m-tag", "processstep", "step-gone", minutes=2),
        ],
    )
    assert {r["id"] for r in rows} == {"m-fk", "m-tag"}


def test_the_two_halves_come_back_newest_first_as_one_list(monkeypatch):
    """The route promised ``order={"createdAt": "desc"}`` and now merges two reads, so the sort has
    to be re-applied — otherwise the tag-only orphans all land in a block after the FK ones and the
    screen's "most recently lost" ordering silently stops being true."""
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
    """The naive shape — ``find_unique`` per media row — turns an admin screen into a minute-long
    request the first time a deployment accumulates a few thousand tagged uploads. Asserted on the
    query log rather than on a clock, because a timing assertion would be flaky and this is exact."""
    clips = [_clip(f"m-{n}", "processstep", f"step-{n}", minutes=n) for n in range(25)]
    _, seen = _orphans(monkeypatch, mediafile=clips, processstep=[])
    step_reads = [entry for entry in seen if entry[0] == "processstep"]
    assert len(step_reads) == 1, f"expected one batched read, got {len(step_reads)}"


def test_an_untagged_row_is_never_an_orphan(monkeypatch):
    """A file attached to nothing is not a file that LOST something. It is already visible under
    ``GET /media`` and putting it on the recovery screen would drown the rows that need rescuing."""
    rows, _ = _orphans(monkeypatch, mediafile=[_clip("m-loose", None, None)])
    assert rows == []


# --------------------------------------------------------------------------------------
# 2. Re-linking one
# --------------------------------------------------------------------------------------


def _relink(monkeypatch, media_id: str, rec_type: str, target_id: str, **tables):
    seen: list[tuple[str, Any]] = []
    monkeypatch.setattr(media_routes, "db", _DB(seen, **tables))
    payload = SimpleNamespace(linkedRecordType=rec_type, linkedRecordId=target_id)
    return asyncio.run(
        media_routes.relink_media(media_id=media_id, payload=payload, current_user=ADMIN)
    )


@pytest.mark.parametrize("rec_type", ["process", "processstep"])
def test_a_process_or_step_can_be_relinked(monkeypatch, rec_type):
    """THE OTHER HALF OF THE DEFECT. ``_relink_delegate`` had no entry for either, so the one route
    that could have rescued these files answered 400 even to an admin holding the exact ids."""
    dead = _clip("m-dead", rec_type, "gone", workshopId="ws-1")
    tables: dict[str, list[Any]] = {"mediafile": [dead]}
    tables[rec_type] = [Row(id="target-1")]
    out = _relink(monkeypatch, "m-dead", rec_type, "target-1", **tables)
    assert out["linkedRecordType"] == rec_type
    assert out["linkedRecordId"] == "target-1"


def test_relinking_a_step_leaves_the_files_workshop_alone(monkeypatch):
    """Neither model carries a ``workshopId``, and ``inherit_parent_workshop`` never writes a NULL
    over a real id. A photograph moved from a dead step to a live one has not changed workshops, and
    clearing the column would make it invisible under every workshop scope — which is the condition
    this recovery screen exists to end."""
    dead = _clip("m-dead", "processstep", "gone", workshopId="ws-1")
    out = _relink(
        monkeypatch, "m-dead", "processstep", "step-live",
        mediafile=[dead], processstep=[Row(id="step-live")],
    )
    assert out["workshopId"] == "ws-1"
