from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.services.datareport import share_links, share_snapshot

NOW = datetime(2026, 11, 5, 10, 0, tzinfo=UTC)


def _media(mid: str, kind: str, size: int = 10, day: datetime | None = None, **extra: object) -> SimpleNamespace:
    return SimpleNamespace(
        id=mid,
        mediaType=kind,
        sizeBytes=size,
        recordedAt=day or NOW,
        createdAt=day or NOW,
        transcriptStatus=extra.pop("transcriptStatus", None),
        transcriptText=extra.pop("transcriptText", None),
        **extra,
    )


def test_tokens_are_stable_unguessable_and_shaped():
    one = share_links.token_for("abc", "secret-one")
    assert one == share_links.token_for("abc", "secret-one")
    assert len(one) == share_links.TOKEN_LENGTH
    assert share_links.well_formed(one)
    assert one != share_links.token_for("abd", "secret-one")
    assert one != share_links.token_for("abc", "secret-two")
    assert share_links.token_hash(one) != one
    assert len(share_links.token_hash(one)) == 64
    assert not share_links.well_formed("short")
    assert not share_links.well_formed(one[:-1] + "!")


def test_url_and_address_hash():
    assert share_links.share_url("https://dw.example.org/", "tok") == "https://dw.example.org/report-share/tok"
    first = share_links.address_hash("ip:1.2.3.4", "s")
    assert first == share_links.address_hash("ip:1.2.3.4", "s")
    assert first != share_links.address_hash("ip:1.2.3.5", "s")
    assert "1.2.3.4" not in first


@pytest.mark.parametrize("value", [0, 31, -1, 2.5, "7", True])
def test_expiry_days_refuse_out_of_range(value):
    with pytest.raises(share_links.Invalid):
        share_links.days_of(value)


def test_expiry_days_accept_the_range():
    assert share_links.days_of(None) == share_links.DAYS_DEFAULT
    assert share_links.days_of(1) == 1
    assert share_links.days_of(30) == 30
    assert share_links.expiry(NOW, 3) == NOW + timedelta(days=3)


def test_options_keep_only_known_keys():
    assert share_links.options_of({"answers": "english", "evil": 1}) == {"answers": "english"}
    assert share_links.options_of(None) == {}
    with pytest.raises(share_links.Invalid):
        share_links.options_of([1])


def test_live_states():
    active = SimpleNamespace(revokedAt=None, expiresAt=NOW + timedelta(hours=1))
    expired = SimpleNamespace(revokedAt=None, expiresAt=NOW - timedelta(seconds=1))
    revoked = SimpleNamespace(revokedAt=NOW, expiresAt=NOW + timedelta(days=1))
    naive = SimpleNamespace(revokedAt=None, expiresAt=(NOW + timedelta(hours=1)).replace(tzinfo=None))
    assert share_links.live(active, NOW) and share_links.state_of(active, NOW) == "active"
    assert not share_links.live(expired, NOW) and share_links.state_of(expired, NOW) == "expired"
    assert not share_links.live(revoked, NOW) and share_links.state_of(revoked, NOW) == "revoked"
    assert share_links.live(naive, NOW)
    assert not share_links.live(None, NOW)


def test_user_agent_is_trimmed():
    assert share_links.user_agent_of(None) is None
    assert share_links.user_agent_of("  a   b ") == "a b"
    assert len(share_links.user_agent_of("x" * 1000) or "") == share_links.USER_AGENT_MAX


def test_media_by_type_counts_in_fixed_order():
    chart = share_snapshot.media_by_type(
        [_media("1", "VIDEO"), _media("2", "IMAGE"), _media("3", "PDF"), _media("4", "IMAGE")]
    )
    assert chart["categories"] == ["Photos", "Videos", "Documents"]
    assert chart["series"][0]["values"] == [2, 1, 1]


def test_recordings_per_day_fill_gaps():
    day = datetime(2026, 11, 1, 6, 0, tzinfo=UTC)
    chart = share_snapshot.recordings_over_time(
        [_media("1", "AUDIO", day=day), _media("2", "VIDEO", day=day + timedelta(days=2)), _media("3", "IMAGE", day=day)]
    )
    assert chart["title"] == "Recordings per day"
    assert chart["categories"] == ["2026-11-01", "2026-11-02", "2026-11-03"]
    assert chart["series"][0]["values"] == [1, 0, 1]


def test_recordings_per_week_past_two_months():
    day = datetime(2025, 1, 6, 6, 0, tzinfo=UTC)
    chart = share_snapshot.recordings_over_time(
        [_media("1", "AUDIO", day=day), _media("2", "AUDIO", day=day + timedelta(days=400))]
    )
    assert chart["title"] == "Recordings per week"
    assert len(chart["categories"]) == share_snapshot.WEEKS_MAX
    assert sum(chart["series"][0]["values"]) == 1
    assert chart["earlierOmitted"] == 1


def test_recordings_chart_without_recordings():
    chart = share_snapshot.recordings_over_time([_media("1", "IMAGE")])
    assert chart["categories"] == [] and chart["series"][0]["values"] == []


def test_withheld_transcripts_never_count_as_ready():
    media = [
        _media("a", "AUDIO", transcriptText="secret words"),
        _media("b", "AUDIO", transcriptStatus="FAILED"),
        _media("c", "VIDEO", transcriptStatus="PROCESSING"),
        _media("d", "AUDIO"),
        _media("e", "IMAGE", transcriptText="ignored"),
    ]
    counts = share_snapshot.transcript_states(media, {}, {"a"})
    assert counts == {"ready": 0, "translating": 1, "failed": 1, "none": 1}
    flags = {"a": {"mediaType": "AUDIO", "transcriptStatus": None, "hasText": True, "translating": True}}
    assert share_snapshot.transcript_states(media[:1], flags, set())["translating"] == 1


def test_section_completion_and_coverage():
    sections = [
        {"key": "A", "label": "Life", "questions": [("q1", "One"), ("q2", "Two")]},
        {"key": "B", "label": "Craft", "questions": [("q3", "Three")]},
        {"key": "C", "label": "Empty", "questions": []},
    ]
    fields, chart = share_snapshot.section_completion(sections, [{"q1", "q2"}, {"q1"}])
    assert fields == {"filled": 3, "total": 6}
    assert chart["categories"] == ["Life", "Craft"]
    values = {s["name"]: s["values"] for s in chart["series"]}
    assert values == {"Complete": [1, 0], "Partial": [1, 0], "Missing": [0, 2]}


def test_snapshot_carries_numbers_only():
    media = [
        _media("a", "AUDIO", size=100, transcriptText="private story"),
        _media("b", "IMAGE", size=50),
    ]
    records = {"artisans": [SimpleNamespace(id="x")], "interviews": [SimpleNamespace(id="i")], "media": media}
    snap = share_snapshot.assemble(
        title="Bagru",
        crumbs=["Repository", "By workshop", "Bagru"],
        generated_by="Kamla",
        generated_at=NOW,
        data_version="7",
        media=media,
        records=records,
        flags={},
        withheld={"a"},
        sections=[{"key": "A", "label": "Life", "questions": [("q1", "One")]}],
        answered_by_unit=[{"q1"}],
        truncated=False,
    )
    text = json.dumps(snap)
    assert "private story" not in text
    by_key = {k["key"]: k for k in snap["kpis"]}
    assert by_key["files"]["value"] == 1
    assert by_key["size"]["value"] == 50
    assert by_key["records"]["value"] == 2
    assert by_key["transcriptsReady"]["value"] == 0
    assert by_key["coverage"]["value"] == 100
    assert snap["withheldTranscripts"] == 1
    assert [c["key"] for c in snap["charts"]] == [
        "mediaByType",
        "recordingsOverTime",
        "completionBySection",
        "transcriptStatus",
    ]
    assert snap["portal"] in ("designer", "fieldRepository")


def test_coverage_without_answers_is_unknown():
    kpis = share_snapshot.kpis([], {}, dict.fromkeys(("ready", "translating", "failed", "none"), 0), {"filled": 0, "total": 0})
    coverage = next(k for k in kpis if k["key"] == "coverage")
    assert coverage["value"] is None


def test_public_payload_merges_expiry():
    row = SimpleNamespace(snapshot={"title": "T"}, expiresAt=NOW, createdAt=NOW)
    out = share_links.public_out(row)
    assert out["title"] == "T" and out["expiresAt"] == NOW.isoformat()


def test_routes_are_registered():
    from app.main import app

    paths = {path: set(ops) for path, ops in app.openapi()["paths"].items()}
    assert paths["/api/data/report/shares"] >= {"post", "get"}
    assert "delete" in paths["/api/data/report/shares/{share_id}"]
    assert "get" in paths["/api/data/report/shares/{share_id}/views"]
    assert "get" in paths["/api/report-share/{token}"]


def test_cover_chart_specs_become_snapshot_charts():
    spec = SimpleNamespace(title="Transcripts", kind="pie", categories=["Ready", "Failed"], series=[("Recordings", [3.0, 1.0])])
    chart = share_snapshot.chart_of("transcriptStatus", spec)
    assert chart == {
        "key": "transcriptStatus",
        "title": "Transcripts",
        "kind": "bar",
        "categories": ["Ready", "Failed"],
        "series": [{"name": "Recordings", "values": [3.0, 1.0]}],
        "unit": "count",
    }
    empty = share_snapshot.chart_of("completionBySection", None, unit="percent")
    assert empty["categories"] == [] and empty["unit"] == "percent"
    assert share_snapshot.chart_of("x", SimpleNamespace(title="T", kind="column", categories=[], series=[]))["kind"] == "column"
