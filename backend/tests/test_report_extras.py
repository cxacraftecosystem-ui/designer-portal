from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from app.services.data_explorer import overview
from app.services.datareport import contract
from app.services.datareport.contract import Link, ReportContext, ReportOptions
from app.services.datareport.sheets import (
    extras_audit,
    extras_changes,
    extras_charts,
    extras_common,
    extras_coverage,
    extras_everything,
    extras_map,
    extras_peer,
)

SCHEMA = Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma"
NOW = datetime(2026, 11, 5, 12, 0, tzinfo=UTC)
EARLIER = NOW - timedelta(days=3)
SHA = "ab" * 32
OPTIONS = {
    "extras_charts": "charts",
    "extras_coverage": "coverage",
    "extras_sparklines": "sparklines",
    "extras_map": "map",
    "extras_changes": "changes",
    "extras_peer": "peer",
    "extras_audit": "audit",
    "extras_everything": "everything",
}


class Links:
    def download(self, media_id: str) -> str:
        return f"https://portal.example/d/{media_id}"

    def open_media(self, media_id: str) -> str:
        return f"https://portal.example/m/{media_id}"

    def open_record(self, kind: str, record_id: str) -> str:
        return f"https://portal.example/r/{kind}/{record_id}"

    def play_from(self, media_id: str, start_ms: int) -> str:
        return f"https://portal.example/m/{media_id}?t={start_ms}"

    def transcript_file(self, media_id: str, fmt: str) -> str:
        return f"https://portal.example/t/{media_id}.{fmt}"

    def section(self, interview_id: str, section_code: str) -> str:
        return f"https://portal.example/s/{interview_id}/{section_code}"

    def data_folder(self, path: str) -> str:
        return f"https://portal.example/data?path={path}"

    async def bundle(self, label: str, media_ids: list[str]) -> str:
        return "https://portal.example/b/1"


def _q(qid: str, code: str, title: str, prompt: str) -> Any:
    return SimpleNamespace(id=qid, sectionCode=code, sectionTitle=title, prompt=prompt, lineageId=None)


QUESTIONS = [_q("q1", "A", "About", "Name?"), _q("q2", "A", "About", "Age?"), _q("q3", "B", "Craft", "Tools?")]


def _material() -> dict[str, Any]:
    loc = SimpleNamespace(
        id="L1", latitude=26.8, longitude=75.5, subjectLatitude=None, subjectLongitude=None,
        district="Jaipur", state="Rajasthan", village="Bagru", placeName=None, address=None,
        pincode="303007", accuracy=12.0,
    )
    a1 = SimpleNamespace(
        id="a1", name="Kamla", craftId="c1", craft=SimpleNamespace(name="Dabu"), status="APPROVED",
        place="Bagru", locationId="L1", location=loc, createdById="u1", reviewedById="u2",
        reviewedAt=EARLIER, reviewNotes=None, recordedAt=EARLIER, createdAt=NOW - timedelta(days=10),
        updatedAt=NOW - timedelta(hours=1), aadhaarFingerprint="f" * 64, notes="Dabu printer",
    )
    a2 = SimpleNamespace(
        id="a2", name="Asha", craftId=None, craft=None, status="PENDING", place="Sanganer",
        locationId=None, location=None, createdById="u1", createdAt=NOW - timedelta(days=10),
        updatedAt=NOW - timedelta(days=10),
    )
    interview = SimpleNamespace(
        id="i1", title="Sitting 1", createdAt=EARLIER, updatedAt=EARLIER, status="PENDING",
        artisans=[SimpleNamespace(artisanId="a1")],
        responses=[
            SimpleNamespace(questionId="q1", answerText="Kamla", question=QUESTIONS[0]),
            SimpleNamespace(questionId="q3", answerText="Block", question=QUESTIONS[2]),
            SimpleNamespace(questionId="q2", answerText="  ", question=QUESTIONS[1]),
        ],
    )
    media = [
        SimpleNamespace(
            id="m1", originalFilename="talk.m4a", mediaType="AUDIO", sizeBytes=1000, artisanId="a1",
            recordedAt=NOW - timedelta(days=1), createdAt=NOW - timedelta(hours=2), uploadedById="u1",
            linkedRecordType=None, linkedRecordId=None, productId=None, toolId=None,
            questionnaireInterviewId=None,
        ),
        SimpleNamespace(
            id="m2", originalFilename="cloth.jpg", mediaType="IMAGE", sizeBytes=500, artisanId=None,
            recordedAt=NOW - timedelta(days=2), createdAt=NOW - timedelta(days=9), uploadedById="u1",
            linkedRecordType="artisan", linkedRecordId="a2", productId=None, toolId=None,
            questionnaireInterviewId=None,
        ),
    ]
    return {"artisans": [a1, a2], "interviews": [interview], "media": media, "workshops": []}


class Delegate:
    def __init__(self, rows: list[Any] | None = None, unique: Any = None) -> None:
        self.rows = rows or []
        self.unique = unique
        self.upserts: list[dict[str, Any]] = []

    async def find_many(self, **kwargs: Any) -> list[Any]:
        where = kwargs.get("where") or {}
        ids = None
        for key in ("id", "recordId", "artisanId"):
            if isinstance(where.get(key), dict):
                ids = set(where[key]["in"])
        if ids is None:
            return list(self.rows)
        return [r for r in self.rows if {getattr(r, "id", None), getattr(r, "recordId", None), getattr(r, "artisanId", None)} & ids]

    async def find_unique(self, **kwargs: Any) -> Any:
        where = kwargs["where"]["userId_path"]
        return self.unique(where["path"]) if callable(self.unique) else self.unique

    async def upsert(self, **kwargs: Any) -> Any:
        self.upserts.append(kwargs)
        return None


def _fake_db(visits: dict[str, datetime] | None = None, links: list[Any] | None = None) -> Any:
    visits = visits or {}

    async def query_raw(sql: str, *args: Any) -> list[dict[str, Any]]:
        if '"talkMs"' in sql:
            return [
                {"id": "m1", "mediaType": "AUDIO", "transcriptStatus": "COMPLETED", "hasText": True,
                 "translating": False, "contentSha256": None, "checksum": SHA.upper(), "talkMs": 65000,
                 "locationId": None, "shaVerifiedAt": EARLIER},
                {"id": "m2", "mediaType": "IMAGE", "transcriptStatus": None, "hasText": False,
                 "translating": False, "contentSha256": "cd" * 32, "checksum": None, "talkMs": 0,
                 "locationId": "L1"},
            ]
        if '"TranscriptVersion" v WHERE' in sql:
            return [{"mediaFileId": "m1", "createdAt": NOW - timedelta(hours=1), "translatedAt": None, "version": 2}]
        return []

    location = SimpleNamespace(
        id="L1", latitude=26.8, longitude=75.5, subjectLatitude=None, subjectLongitude=None,
        district="Jaipur", state="Rajasthan", village=None, placeName="Bagru", address=None,
        pincode=None, accuracy=None,
    )
    return SimpleNamespace(
        query_raw=query_raw,
        questionnairequestion=Delegate(QUESTIONS),
        user=Delegate([SimpleNamespace(id="u1", name="Ravi"), SimpleNamespace(id="u2", name="Meena")]),
        location=Delegate([location]),
        reviewlog=Delegate(
            [SimpleNamespace(id="r1", recordId="a1", recordType="ARTISAN", status="APPROVED", reviewerId="u2",
                             reason=None, notes="ok", createdAt=NOW - timedelta(hours=3))]
        ),
        recordrevision=Delegate(
            [SimpleNamespace(id="v1", recordId="a1", editedById="u1", createdAt=NOW - timedelta(hours=1),
                             changes={"notes": {"from": "a", "to": "b"}, "aadhaarFingerprint": "x"})]
        ),
        datafoldervisit=Delegate(unique=lambda path: SimpleNamespace(visitedAt=visits[path]) if path in visits else None),
        crossportalartisanlink=Delegate(links or []),
    )


@pytest.fixture
def fake(monkeypatch: pytest.MonkeyPatch) -> Any:
    def install(**kwargs: Any) -> Any:
        db = _fake_db(**kwargs)
        for module in (overview, extras_common, extras_coverage, extras_map, extras_changes, extras_audit, extras_peer):
            monkeypatch.setattr(module, "db", db)
        return db

    return install


def _ctx(portal: str = "designer", **options: Any) -> ReportContext:
    return ReportContext(
        portal=portal,
        user=SimpleNamespace(id="u1", email="ravi@example.org", role="ADMIN"),
        scope=None,
        path="by-workshop/w1",
        folder_name="Bagru",
        material={"records": _material()},
        options=ReportOptions(**options),
        links=Links(),
        t=lambda _key, default: default,
        data_version="v1",
        generated_at=NOW.isoformat(),
    )


def _schema_fields(text: str) -> dict[str, set[str]]:
    models: dict[str, set[str]] = {}
    current = None
    for line in text.splitlines():
        head = re.match(r"^model (\w+) \{", line)
        if head:
            current = head.group(1)
            models[current] = set()
            continue
        if line.startswith("}"):
            current = None
            continue
        field = re.match(r"^\s+(\w+)\s+\S", line)
        if current and field and not line.strip().startswith(("//", "@@")):
            models[current].add(field.group(1))
    return models


@pytest.mark.parametrize("portal", ["designer", "fieldRepository"])
def test_every_extra_is_an_optional_plugin_in_both_portals(portal: str) -> None:
    found = {p.key: p for p in contract.plugins(portal) if p.key.startswith("extras_")}
    assert {k: p.option for k, p in found.items()} == OPTIONS
    assert all(p.order >= 100 for p in found.values())
    keys = dict(contract.optional_keys(portal))
    assert set(OPTIONS.values()) <= set(keys)


async def _all_sheets(ctx: ReportContext) -> list[Any]:
    sheets = []
    for plugin in contract.plugins(ctx.portal):
        if plugin.key.startswith("extras_"):
            sheets.extend(await plugin.build(ctx))
    return sheets


async def test_every_column_source_names_a_real_field(fake: Any) -> None:
    fake()
    models = _schema_fields(SCHEMA.read_text(encoding="utf-8"))
    for sheet in await _all_sheets(_ctx()):
        keys = [c.key for c in sheet.columns]
        assert len(keys) == len(set(keys)), sheet.key
        for column in sheet.columns:
            for source in column.sources:
                model, _, field = source.partition(".")
                assert field in models.get(model, set()), f"{sheet.key}.{column.key}: {source}"
        for name in sheet.sparkline_columns + sheet.databar_columns:
            assert name in keys
        rows = list(sheet.rows)
        for row in rows:
            assert set(row) <= set(keys), sheet.key


async def test_cover_charts(fake: Any) -> None:
    fake()
    ctx = _ctx()
    assert await extras_charts.build(ctx) == []
    titles = {c.title: c for c in ctx.cover_charts}
    assert titles["Files by type"].categories == ["Photos", "Recordings"]
    assert titles["Recordings per day"].series[0][1] == [1.0]
    assert titles["Transcripts"].categories == ["Ready"]
    section = titles["Artisans complete, by section"]
    assert section.categories == ["About", "Craft"]
    assert section.series[0][1] == [0.0, 50.0]


async def test_coverage_grid_links_each_section(fake: Any) -> None:
    fake()
    [sheet] = await extras_coverage.build_coverage(_ctx())
    rows = {r["artisanId"]: r for r in sheet.rows}
    assert rows["a1"]["s0"] == Link("Partial", "https://portal.example/s/i1/A")
    assert rows["a1"]["s1"] == Link("Complete", "https://portal.example/s/i1/B")
    assert rows["a2"]["s0"] == "Missing"
    assert [r["artisanId"] for r in sheet.rows] == ["a2", "a1"]
    assert {c.kind for c in sheet.columns if c.group == "sections"} == {"status"}


async def test_completeness_sparklines_and_bars(fake: Any) -> None:
    fake()
    [sheet] = await extras_coverage.build_completeness(_ctx())
    row = next(r for r in sheet.rows if r["artisanId"] == "a1")
    assert (row["ns0"], row["ns1"]) == (0.5, 1.0)
    assert (row["files"], row["bytes"], row["talkMs"]) == (1, 1000, 65000)
    assert sheet.sparkline_columns == ["trend"]
    assert [c.key for c in sheet.columns if c.group == "trend"] == ["ns0", "ns1"]
    assert {"bytes", "talkMs"} <= set(sheet.databar_columns)


async def test_map_groups_by_district(fake: Any) -> None:
    fake()
    districts, detail = await extras_map.build(_ctx())
    by = {r["district"]: r for r in districts.rows}
    assert by["Jaipur"]["places"] == 2
    assert by["Jaipur"]["artisans"] == 1 and by["Jaipur"]["files"] == 1
    assert by[extras_map.NO_DISTRICT]["places"] == 1
    urls = [r["map"].url for r in detail.rows]
    assert "https://www.google.com/maps/search/?api=1&query=26.800000,75.500000" in urls
    assert any(u.endswith("query=Sanganer") for u in urls)


async def test_changes_first_report_lists_everything(fake: Any) -> None:
    fake()
    [sheet] = await extras_changes.build(_ctx())
    seen = {(r["change"], r["recordId"]) for r in sheet.rows}
    assert {("Added", "a1"), ("Added", "a2"), ("Added", "m2"), ("Reviewed", "a1")} <= seen
    assert "first report" in sheet.banners[0].text


async def test_changes_since_last_report(fake: Any) -> None:
    db = fake(visits={"report:by-workshop/w1": NOW - timedelta(days=1)})
    [sheet] = await extras_changes.build(_ctx())
    seen = {(r["change"], r["recordId"]) for r in sheet.rows}
    assert ("Changed", "a1") in seen
    assert ("Added", "m1") in seen
    assert ("Reviewed", "a1") in seen
    assert ("Transcribed", "m1") in seen
    assert ("Changed", "a2") not in seen
    assert "last report" in sheet.banners[0].text
    assert db.datafoldervisit.upserts == []
    ctx = _ctx()
    await extras_changes.build(ctx)
    [hook] = ctx.delivered
    await hook()
    assert db.datafoldervisit.upserts[0]["where"]["userId_path"]["path"] == "report:by-workshop/w1"


async def test_audit_names_people_and_checksums(fake: Any) -> None:
    fake()
    summary, trail = await extras_audit.build(_ctx())
    rows = {r["recordId"]: r for r in summary.rows}
    assert rows["a1"]["approvedBy"] == "Meena" and rows["a1"]["edits"] == 1
    assert rows["a1"]["recordedBy"] == "Ravi"
    assert rows["m1"]["sha256"] == SHA
    assert rows["m2"]["sha256"] == "cd" * 32
    edit = next(e for e in trail.rows if e["event"] == "Edit")
    assert "aadhaarFingerprint" not in (edit["detail"] or "") and "notes" in edit["outcome"]


async def test_everything_is_flat_and_keeps_secrets_out(fake: Any) -> None:
    fake()
    [sheet] = await extras_everything.build(_ctx())
    rows = list(sheet.rows)
    fields = {(r["recordId"], r["field"]) for r in rows}
    assert ("a1", "name") in fields and ("a1", "aadhaarFingerprint") not in fields
    assert ("i1", "Name?") in fields and ("i1", "Age?") not in fields
    assert ("a1", "location") not in fields and ("a1", "craft") not in fields
    assert ("m1", "download") in fields
    size = next(r for r in rows if (r["recordId"], r["field"]) == ("m1", "sizeBytes"))
    assert size["number"] == 1000 and size["source"] == "MediaFile.sizeBytes"


async def test_filtered_rows_follow_the_search(fake: Any) -> None:
    fake()
    ctx = _ctx(rows="filtered", q="kamla")
    assert [a.id for a in extras_common.records(ctx, "artisans")] == ["a1"]
    assert len(extras_common.records(ctx, "artisans", filtered=False)) == 2


async def test_peer_sheet_off_and_on(fake: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    fake(links=[SimpleNamespace(artisanId="a1", remoteArtisanId="r9", matchedBy="PEHCHAN")])
    settings = SimpleNamespace()
    monkeypatch.setattr(extras_peer, "get_settings", lambda: settings)
    monkeypatch.setattr(extras_peer.cross_portal, "search_active", lambda _s: False)
    [sheet] = await extras_peer.build(_ctx())
    assert list(sheet.rows) == [] and "switched off" in (sheet.note or "")

    monkeypatch.setattr(extras_peer.cross_portal, "search_active", lambda _s: True)
    monkeypatch.setattr(extras_peer.us, "may_search_across", lambda _u: True)

    async def ask(_settings: Any, _user: Any, **kwargs: Any) -> tuple[str, dict[str, Any]]:
        assert kwargs["q"] == "Kamla"
        return extras_peer.us.STATUS_OK, {
            "groups": [
                {"type": "artisans", "items": [{"id": "r9", "title": "Kamla", "openUrl": "https://fr.example/a/r9"}]},
                {"type": "media", "items": [
                    {"id": "x1", "title": "Mine", "linkedArtisanIds": ["a1"], "recordDate": "2026-10-01T00:00:00+00:00"},
                    {"id": "x2", "title": "Someone else", "linkedArtisanIds": ["zz"]},
                ]},
            ]
        }

    async def keep(groups: list[Any]) -> list[Any]:
        return groups

    monkeypatch.setattr(extras_peer.us, "ask_peer", ask)
    monkeypatch.setattr(extras_peer, "_keep_consented", keep)
    [sheet] = await extras_peer.build(_ctx())
    titles = [r["title"] for r in sheet.rows]
    assert Link("Kamla", "https://fr.example/a/r9") in titles and "Mine" in titles
    assert "Someone else" not in titles
    assert all(r["matchedBy"] == "Pehchan" for r in sheet.rows)

    async def down(*_args: Any, **_kwargs: Any) -> tuple[str, dict[str, Any]]:
        return extras_peer.us.STATUS_UNAVAILABLE, {}

    monkeypatch.setattr(extras_peer.us, "ask_peer", down)
    [sheet] = await extras_peer.build(_ctx())
    assert list(sheet.rows) == [] and "couldn't be reached" in (sheet.note or "")


async def test_peer_sheet_without_links(fake: Any) -> None:
    fake()
    [sheet] = await extras_peer.build(_ctx("fieldRepository"))
    assert list(sheet.rows) == [] and "linked" in (sheet.note or "")


def test_every_extra_has_a_catalog_matching_its_sheets() -> None:
    for plugin in contract.plugins("designer"):
        if not plugin.key.startswith("extras_"):
            continue
        listed = contract.catalog_of(plugin)
        assert listed is not None, plugin.key
        for key, name, columns in listed:
            assert key and name and columns, plugin.key
            assert all("." in source for c in columns for source in c.sources)


async def test_sheet_keys_match_the_catalog(fake: Any) -> None:
    fake()
    ctx = _ctx()
    for plugin in contract.plugins("designer"):
        if not plugin.key.startswith("extras_"):
            continue
        built = {s.key for s in await plugin.build(ctx)}
        assert built == {k for k, _n, _c in contract.catalog_of(plugin)}, plugin.key


async def test_facts_come_from_material_when_core_carries_them(fake: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    fake()
    ctx = _ctx()
    for m in ctx.material["records"]["media"]:
        m.contentSha256 = SHA if m.id == "m1" else None
        m.checksum = None
        m.transcriptStatus = "FAILED" if m.id == "m1" else None
        m.durationMs = 4000
        m.hasTranscript = False

    async def refuse(*_args: Any) -> list[Any]:
        raise AssertionError("no MediaFile query when material carries the facts")

    monkeypatch.setattr(extras_common.db, "query_raw", refuse)
    facts = await extras_common.media_facts(ctx)
    assert extras_common.sha256_of(facts["m1"]) == SHA
    assert extras_common.transcript_state(facts["m1"], "AUDIO") == "Failed"
    assert facts["m1"]["talkMs"] == 4000


def test_recordings_switch_to_weeks_over_long_spans() -> None:
    media = [
        SimpleNamespace(id=str(i), mediaType="AUDIO", recordedAt=NOW - timedelta(days=d))
        for i, d in enumerate((0, 1, 100))
    ]
    chart = extras_charts.recordings_per_day(media)
    assert chart is not None and chart.title == "Recordings per week"
    assert sum(chart.series[0][1]) == 3.0
    short = extras_charts.recordings_per_day(media[:2])
    assert short is not None and short.categories == [
        (NOW - timedelta(days=1)).astimezone(extras_charts.IST).strftime("%d %b"),
        NOW.astimezone(extras_charts.IST).strftime("%d %b"),
    ]
