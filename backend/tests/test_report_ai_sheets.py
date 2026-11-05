from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import transcript_export, transcript_insights, transcript_portal
from app.services.datareport import ai_cache, contract
from app.services.datareport.contract import Link, ReportContext, ReportOptions
from app.services.datareport.sheets import ai_glossary, ai_profiles, ai_quotes

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


class Links:
    def download(self, media_id: str) -> str:
        return f"/d/{media_id}"

    def open_media(self, media_id: str) -> str:
        return f"/m/{media_id}"

    def open_record(self, kind: str, record_id: str) -> str:
        return f"/r/{kind}/{record_id}"

    def play_from(self, media_id: str, start_ms: int) -> str:
        return f"/m/{media_id}?t={start_ms}"

    def transcript_file(self, media_id: str, fmt: str) -> str:
        return f"/t/{media_id}.{fmt}"

    def section(self, interview_id: str, section_code: str) -> str:
        return f"/s/{interview_id}/{section_code}"

    def data_folder(self, path: str) -> str:
        return f"/f/{path}"

    async def bundle(self, label: str, media_ids: list[str]) -> str:
        return "/b/1"


class Cache:
    def __init__(self) -> None:
        self.store: dict[str, Any] = {}
        self.builds = 0

    async def __call__(self, key: str, factory: Any) -> Any:
        if key not in self.store:
            self.builds += 1
            self.store[key] = await factory()
        return self.store[key]


def media(
    mid: str, *, artisan: str | None = "a1", text: bool = True, kind: str = "AUDIO", **extra: Any
) -> Any:
    return SimpleNamespace(
        id=mid,
        originalFilename=f"{mid}.m4a",
        mediaType=kind,
        hasTranscript=text,
        artisanId=artisan,
        artisan=None,
        questionnaireInterviewId=extra.pop("interview", None),
        recordedAt=datetime(2026, 11, 1, tzinfo=UTC),
        createdAt=datetime(2026, 11, 1, tzinfo=UTC),
        sizeBytes=1024,
        **extra,
    )


def artisan(aid: str, name: str, **extra: Any) -> Any:
    base = {
        "id": aid,
        "name": name,
        "localName": None,
        "gender": "Female",
        "phone": "9876543210",
        "phoneCountryCode": "+91",
        "email": None,
        "place": "Bhuj",
        "address": None,
        "notes": None,
        "dateOfBirth": datetime(1980, 1, 1, tzinfo=UTC),
        "craftStartDate": None,
        "experienceYears": 30,
        "experienceMonths": 6,
        "dos": None,
        "donts": None,
        "status": "APPROVED",
        "reviewNotes": None,
        "reviewedAt": None,
        "recordedAt": datetime(2026, 10, 1, tzinfo=UTC),
        "pehchanCardAvailable": False,
        "pehchanCardNumber": None,
        "craft": SimpleNamespace(name="Ajrakh"),
        "createdBy": SimpleNamespace(name="Asha"),
        "workshops": [SimpleNamespace(workshop=SimpleNamespace(title="Kutch 2026"))],
        "location": SimpleNamespace(
            latitude=23.25,
            longitude=69.66,
            village="Dhamadka",
            placeName=None,
            district="Kutch",
            state="Gujarat",
            pincode=None,
        ),
    }
    base.update(extra)
    return SimpleNamespace(**base)


def make_ctx(records: dict[str, Any], *, nested: bool = True, **options: Any) -> ReportContext:
    material = {"records": records, "sheets": []} if nested else {**records, "sheets": []}
    return ReportContext(
        portal="designer",
        user=SimpleNamespace(id="u1"),
        scope=None,
        path="/",
        folder_name="Repository",
        material=material,
        options=ReportOptions(**options),
        links=Links(),
        t=lambda key, default: default,
        data_version="7:42",
        generated_at="2026-11-05T00:00:00Z",
        cache=Cache(),
    )


def rec(
    mid: str, *, quotes: list[dict[str, Any]] | None = None, withheld: bool = False
) -> dict[str, Any]:
    return {
        "mediaId": mid,
        "file": f"{mid}.m4a",
        "withheld": "CONSENT_REFUSED" if withheld else None,
        "source": None if withheld else "TURNS",
        "languages": [{"code": "gu", "label": "Gujarati"}],
        "turns": [
            {"startMs": 0, "text": "નમસ્તે", "english": "Hello"},
            {"startMs": 61_000, "text": "અમે ગળી થી રંગીએ છીએ.", "english": "We dye with indigo."},
        ],
        "quotes": quotes or [],
    }


def test_all_three_register_for_both_portals() -> None:
    for portal in ("designer", "fieldRepository"):
        found = {p.key: p for p in contract.plugins(portal)}
        assert found["quotes"].option == "quotes"
        assert found["glossary"].option == "glossary"
        assert found["profiles"].option == "profiles"
        assert all(found[k].order >= 100 for k in ("quotes", "glossary", "profiles"))
    assert ("quotes", "Quotes") in contract.optional_keys("fieldRepository")


def test_each_plugin_has_a_static_catalog_with_sources() -> None:
    for plugin in (
        ai_quotes.QuotesSheet(),
        ai_glossary.GlossarySheet(),
        ai_profiles.ProfileSheets(),
    ):
        [sheet] = plugin.catalog()
        keys = [c.key for c in sheet.columns]
        assert len(keys) == len(set(keys))
        assert {"play", "open"} & set(keys)
        assert sheet.rows == []


async def test_fixed_text_goes_through_the_translator(monkeypatch: pytest.MonkeyPatch) -> None:
    async def query_raw(sql: str, ids: list[str]) -> list[dict[str, Any]]:
        return []

    monkeypatch.setattr("app.core.db.db", SimpleNamespace(query_raw=query_raw))
    ctx = make_ctx(_profile_records(1))
    hindi = {
        "sheet.profile": "प्रोफ़ाइल",
        "profile.section.consent": "सहमति",
        "status.not_recorded": "दर्ज नहीं",
    }
    ctx.t = hindi.get
    [sheet] = await ai_profiles.ProfileSheets().build(ctx)
    assert sheet.name == "प्रोफ़ाइल · Artisan 000"
    consent = [r for r in sheet.rows if r["section"] == "सहमति"]
    assert consent and all(r["status"] == "दर्ज नहीं" for r in consent)
    assert all("_fixed" not in r for r in sheet.rows)


def test_records_read_nested_or_flat() -> None:
    for nested in (True, False):
        ctx = make_ctx({"media": [media("m1")]}, nested=nested)
        assert ai_cache.media_ids(ctx) == ["m1"]


def test_sheet_names_are_unique_short_and_legal() -> None:
    taken: set[str] = set()
    first = ai_cache.sheet_name(
        "Ramesh [Bhai]: Ajrakh/Dabu printer of Dhamadka village", taken, prefix="01 "
    )
    second = ai_cache.sheet_name(
        "Ramesh [Bhai]: Ajrakh/Dabu printer of Dhamadka village", taken, prefix="01 "
    )
    assert first != second
    for name in (first, second):
        assert len(name) <= 31
        assert not set(name) & set("[]:*?/\\")


def test_find_start_matches_quote_to_its_turn() -> None:
    turns = rec("m1")["turns"]
    assert ai_cache.find_start(turns, "“અમે ગળી થી રંગીએ છીએ.”") == 61_000
    assert ai_cache.find_start(turns, None, "we dye with indigo.") == 61_000
    assert ai_cache.find_start(turns, "absent words") is None


async def test_quotes_sheet_pairs_original_with_english_and_plays_from(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    loads: list[list[str]] = []

    async def load(ids: list[str], **_: Any) -> dict[str, Any]:
        loads.append(list(ids))
        return {
            "m1": rec(
                "m1",
                quotes=[
                    {
                        "original": "અમે ગળી થી રંગીએ છીએ.",
                        "english": "We dye with indigo.",
                        "speaker": "Kesar",
                    }
                ],
            ),
            "m2": rec("m2", withheld=True),
        }

    async def no_queue(ctx: Any, ids: list[str]) -> int:
        return 0

    monkeypatch.setattr(transcript_export, "load", load)
    monkeypatch.setattr(ai_quotes, "_queue_missing", no_queue)
    ctx = make_ctx({"media": [media("m1"), media("m2")], "artisans": [artisan("a1", "Kesar Ben")]})
    [sheet] = await ai_quotes.QuotesSheet().build(ctx)
    again = await ai_quotes.QuotesSheet().build(ctx)
    assert ctx.cache.builds == 1 and len(loads) == 1 and again[0].rows == sheet.rows
    keys = [c.key for c in sheet.columns]
    assert keys.index("english") == keys.index("original") + 1
    assert all(c.source for c in sheet.columns)
    [row] = sheet.rows
    assert row["original"] == "અમે ગળી થી રંગીએ છીએ." and row["english"] == "We dye with indigo."
    assert row["artisan"] == "Kesar Ben" and row["at"] == 61_000
    assert row["play"] == Link("Play from", "/m/m1?t=61000")
    assert "consent" in (sheet.note or "")
    top = await ai_quotes.top_quotes(ctx, 5)
    assert top == [
        {
            "mediaId": "m1",
            "file": "m1.m4a",
            "artisan": "Kesar Ben",
            "speaker": "Kesar",
            "original": "અમે ગળી થી રંગીએ છીએ.",
            "english": "We dye with indigo.",
            "language": "Gujarati",
            "startMs": 61_000,
        }
    ]
    assert len(loads) == 1


async def test_quotes_follow_the_language_and_link_options(monkeypatch: pytest.MonkeyPatch) -> None:
    async def load(ids: list[str], **_: Any) -> dict[str, Any]:
        return {"m1": rec("m1", quotes=[{"original": "નમસ્તે", "english": "Hello", "speaker": None}])}

    async def no_queue(ctx: Any, ids: list[str]) -> int:
        return 0

    monkeypatch.setattr(transcript_export, "load", load)
    monkeypatch.setattr(ai_quotes, "_queue_missing", no_queue)
    [sheet] = await ai_quotes.QuotesSheet().build(
        make_ctx({"media": [media("m1")]}, answers="original", links=False)
    )
    keys = {c.key for c in sheet.columns}
    assert "original" in keys and "english" not in keys and "play" not in keys
    assert "play" not in sheet.rows[0]


async def test_recordings_without_quotes_are_queued_once_per_data_version(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []

    async def load(ids: list[str], **_: Any) -> dict[str, Any]:
        return {"m1": rec("m1")}

    async def queue(ctx: Any, ids: list[str]) -> int:
        calls.append(list(ids))
        return len(ids)

    monkeypatch.setattr(transcript_export, "load", load)
    monkeypatch.setattr(ai_quotes, "_queue_missing", queue)
    ctx = make_ctx({"media": [media("m1")]})
    [sheet] = await ai_quotes.QuotesSheet().build(ctx)
    await ai_quotes.QuotesSheet().build(ctx)
    assert await ai_quotes.top_quotes(ctx) == []
    assert calls == [["m1"]]
    assert sheet.rows == [] and sheet.banners and "1 more" in sheet.banners[0].text


async def test_missing_quotes_queue_only_with_the_flag_on_and_consent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    queued: list[str] = []
    fake_db = SimpleNamespace(mediafile=SimpleNamespace(find_unique=None))

    async def find_unique(where: dict[str, Any]) -> Any:
        return SimpleNamespace(id=where["id"])

    fake_db.mediafile.find_unique = find_unique

    async def none(ids: list[str]) -> dict[str, Any]:
        return {}

    async def jobs(ids: list[str]) -> dict[str, Any]:
        return {"m3": SimpleNamespace(status="FAILED")}

    async def may_send(m: Any) -> tuple[bool, str | None]:
        return (m.id != "m2", None if m.id != "m2" else "Refused")

    async def enqueue(mid: str, *, requested_by_id: str | None = None, priority: int = 80) -> Any:
        queued.append(mid)
        return SimpleNamespace(id="job")

    monkeypatch.setattr("app.core.db.db", fake_db)
    monkeypatch.setattr(transcript_insights, "current_insights", none)
    monkeypatch.setattr(transcript_insights, "latest_jobs", jobs)
    monkeypatch.setattr(transcript_insights, "enqueue", enqueue)
    monkeypatch.setattr(transcript_portal, "may_send", may_send)
    ctx = make_ctx({})

    monkeypatch.setattr(transcript_insights, "enabled", lambda: False)
    assert await ai_quotes._queue_missing(ctx, ["m1", "m2", "m3"]) == 0 and queued == []

    monkeypatch.setattr(transcript_insights, "enabled", lambda: True)
    assert await ai_quotes._queue_missing(ctx, ["m1", "m2", "m3"]) == 1
    assert queued == ["m1"]


async def test_glossary_lists_every_sighting_with_its_link(monkeypatch: pytest.MonkeyPatch) -> None:
    seen: list[list[str]] = []

    async def withheld(ids: list[str], **_: Any) -> set[str]:
        return {"m2"}

    async def query_raw(sql: str, ids: list[str]) -> list[dict[str, Any]]:
        seen.append(list(ids))
        return [
            {
                "id": "s2",
                "mediaFileId": "m1",
                "startMs": 5_000,
                "termId": "t2",
                "term": "Ajrakh",
                "gloss": None,
                "occurrences": 9,
                "craft": "Ajrakh",
                "namesCraft": "Ajrakh",
            },
            {
                "id": "s1",
                "mediaFileId": "m1",
                "startMs": None,
                "termId": "t1",
                "term": "dabu",
                "gloss": "mud resist",
                "occurrences": 4,
                "craft": "Ajrakh",
                "namesCraft": None,
            },
            {
                "id": "s3",
                "mediaFileId": "m3",
                "startMs": 9_000,
                "termId": "t1",
                "term": "dabu",
                "gloss": "mud resist",
                "occurrences": 4,
                "craft": "Ajrakh",
                "namesCraft": None,
            },
        ]

    monkeypatch.setattr(transcript_export, "withheld_ids", withheld)
    monkeypatch.setattr("app.core.db.db", SimpleNamespace(query_raw=query_raw))
    ctx = make_ctx(
        {
            "media": [media("m1"), media("m2"), media("m3"), media("m4", text=False)],
            "artisans": [artisan("a1", "Kesar")],
        }
    )
    [sheet] = await ai_glossary.GlossarySheet().build(ctx)
    assert seen == [["m1", "m3"]]
    assert [r["term"] for r in sheet.rows] == ["Ajrakh", "dabu", "dabu"]
    assert sheet.rows[1]["meaning"] == "mud resist" and sheet.rows[1]["inReport"] == 2
    assert sheet.rows[0]["namesCraft"] == "Ajrakh"
    assert sheet.rows[1]["play"] == Link("Play from", "/m/m1?t=0")
    assert sheet.rows[2]["play"] == Link("Play from", "/m/m3?t=9000")
    assert all(c.source for c in sheet.columns)
    await ai_glossary.GlossarySheet().build(ctx)
    assert len(seen) == 1


def _profile_records(count: int) -> dict[str, Any]:
    people = [artisan(f"a{i}", f"Artisan {i:03d}") for i in range(count)]
    tool = SimpleNamespace(
        id="t1",
        toolkitName="Block",
        englishName=None,
        localName="Bibu",
        material="Teak",
        processUsedIn="Printing",
        status="PENDING",
        recordedAt=None,
        artisanId="a0",
        artisanLinks=[SimpleNamespace(artisanId="a1")],
    )
    product = SimpleNamespace(
        id="p1",
        productName="Stole",
        localName=None,
        productType="FINISHED_GOOD",
        status="APPROVED",
        recordedAt=None,
        artisanId="a0",
    )
    process = SimpleNamespace(
        id="pr1",
        name="Mud resist",
        status="APPROVED",
        recordedAt=None,
        steps=[1, 2, 3],
        product=SimpleNamespace(productName="Stole", artisanId="a0"),
    )
    interview = SimpleNamespace(id="i1", artisans=[SimpleNamespace(artisanId="a1", artisan=None)])
    files = [
        media("m1", artisan="a0"),
        media("m2", artisan="a0", kind="IMAGE", text=False),
        media("m3", artisan=None, interview="i1"),
    ]
    return {
        "artisans": people,
        "tools": [tool],
        "products": [product],
        "processes": [process],
        "interviews": [interview],
        "media": files,
    }


async def test_profiles_carry_facts_consent_tools_processes_and_recordings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def query_raw(sql: str, ids: list[str]) -> list[dict[str, Any]]:
        assert "consent_current_v" in sql
        return [
            {
                "subjectId": "a0",
                "purpose": "EXTERNAL_PROCESSING",
                "state": "GRANTED",
                "basis": "SIGNED_FORM",
                "recordedAt": datetime(2026, 9, 1, tzinfo=UTC),
                "note": None,
            }
        ]

    monkeypatch.setattr("app.core.db.db", SimpleNamespace(query_raw=query_raw))
    sheets = await ai_profiles.ProfileSheets().build(make_ctx(_profile_records(2)))
    assert [s.name for s in sheets] == ["Profile · Artisan 000", "Profile · Artisan 001"]
    first = sheets[0].rows
    sections = {r["section"] for r in first}
    assert {"Key facts", "Consent", "Tools", "Products", "Processes", "Recordings"} <= sections
    assert "Photo" not in sections
    consent = [r for r in first if r["section"] == "Consent"]
    assert consent[0]["status"] == "Granted" and consent[0]["detail"] == "Signed form"
    assert consent[1]["status"] == "Not recorded"
    experience = next(r for r in first if r["item"] == "Experience")
    assert experience["detail"] == "30 years 6 months"
    assert next(r for r in first if r["section"] == "Processes")["detail"] == "For Stole · 3 steps"
    audio = next(r for r in first if r["item"] == "m1.m4a")
    assert audio["download"] == Link("Download", "/d/m1") and audio["transcript"] == Link(
        "Transcript", "/t/m1.docx"
    )
    second = sheets[1].rows
    assert any(r["section"] == "Tools" for r in second)
    assert any(r["item"] == "m3.m4a" for r in second)


async def test_profiles_cap_with_a_note_and_show_thumbnails_on_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def query_raw(sql: str, ids: list[str]) -> list[dict[str, Any]]:
        return []

    monkeypatch.setattr("app.core.db.db", SimpleNamespace(query_raw=query_raw))
    sheets = await ai_profiles.ProfileSheets().build(
        make_ctx(_profile_records(55), thumbnails=True)
    )
    assert len(sheets) == ai_profiles.PROFILES_CAP
    assert "50 of 55" in (sheets[0].note or "") and sheets[1].note is None
    assert len({s.name.casefold() for s in sheets}) == len(sheets)
    assert len({s.key for s in sheets}) == len(sheets)
    assert sheets[0].rows[0]["section"] == "Photo"
    assert await ai_profiles.ProfileSheets().build(make_ctx({})) == []
