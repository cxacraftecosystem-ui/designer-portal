"""Interview summaries, key quotes and translations (services/transcript_insights).

Runs everywhere: the database is ``transcript_insights_fakes.FakeDb`` and every provider call is a
stub of ``ai._run_chat_verb``, so nothing here leaves the machine.
``test_transcript_insights_db.py`` covers what only a real Postgres can show (the unique index, the
cascade, the enum value).

The designer extras sit beside the shared contract: the read gate (an insight is a copy of the
transcript), the artisan's consent re-read at send time, and the daily AI allowance on Regenerate.
"""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError
from transcript_insights_fakes import FakeDb, Row

from app.ai_features import Capability, probe
from app.ai_features.settings import build_settings
from app.services import dictation_consent, transcript_insights as ti
from app.services.dictation_consent import SendDecision, SendVerdict

ON = {"AI_FEATURES_ENABLED": "true", "AI_TRANSCRIPT_INSIGHTS_ENABLED": "true"}

HINDI = "हमारे गाँव में दाबू छपाई होती है।\n\nमिट्टी रात भर भिगोई जाती है। कीमत दो सौ रुपये है।"
ENGLISH = (
    "We do dabu printing in our village.\n\nThe mud is soaked overnight. The price is two hundred "
    "rupees."
)
REFUSAL = dictation_consent.gate_refusal(
    dictation_consent.DictationConsent.REFUSED, dictation_consent.INSIGHTS
)


def summary_reply(language: str = "hi", quotes: list[dict[str, Any]] | None = None) -> str:
    return json.dumps(
        {
            "primaryLanguage": language,
            "summary": [
                "Dabu printing is done in the village.",
                "Mud is soaked overnight.",
                "The price is 200 rupees.",
            ],
            "quotes": quotes
            if quotes is not None
            else [
                {
                    "original": "मिट्टी रात भर भिगोई जाती है।",
                    "english": "The mud is soaked overnight.",
                    "speaker": "Artisan",
                }
            ],
        },
        ensure_ascii=False,
    )


async def _noop(*_a: Any, **_k: Any) -> int:
    return 0


@pytest.fixture
def fake(monkeypatch):
    db = FakeDb()
    from app.api.routes import transcript_insights as routes
    from app.services import ai_verb_cap, design_workshop_posts, media_queue

    for module in (ti, media_queue, routes):
        monkeypatch.setattr(module, "db", db)
    monkeypatch.setattr(ti, "enabled", lambda: True)
    monkeypatch.setattr(
        ti,
        "get_settings",
        lambda: SimpleNamespace(media_queue_job_max_attempts=3, openai_chat_model="gpt-4o-mini"),
    )
    db.consent = SendVerdict(SendDecision.NOT_WORKSHOP_MATERIAL)

    async def verdict(_media, **kwargs):
        db.consent_sends = [*getattr(db, "consent_sends", []), kwargs.get("send")]
        return db.consent

    monkeypatch.setattr(dictation_consent, "transcription_verdict", verdict)
    # Everyone may read every transcript unless a test narrows it (see the read-gate tests).
    db.scope = (None, frozenset())

    async def scope(_viewer):
        return db.scope

    monkeypatch.setattr(routes, "media_url_scope", scope)
    monkeypatch.setattr(design_workshop_posts, "refuse_a_holders_media_write", _noop)
    db.spent = []

    async def spend(user_id, day, verb):
        db.spent.append((user_id, verb))

    db.cap = SimpleNamespace(limit=None, used=0)

    async def load_allowance(_user_id, **_k):
        return ai_verb_cap.VerbAllowance(day="2026-10-18", limit=db.cap.limit, used=db.cap.used)

    monkeypatch.setattr(ai_verb_cap, "spend", spend)
    monkeypatch.setattr(ai_verb_cap, "load_allowance", load_allowance)
    return db


@pytest.fixture
def provider(monkeypatch):
    """Stub ``ai._run_chat_verb``. ``calls`` records (task, user_id, temperature)."""
    from app.services import ai

    state = SimpleNamespace(calls=[], replies=[], fail_on={}, unavailable=False)

    async def run_chat_verb(*, what, system, user, settings, temperature, task, user_id=None):
        state.calls.append((task, user_id, temperature))
        index = len(state.calls)
        if state.unavailable:
            return {
                "available": False,
                "status": "UNAVAILABLE",
                "text": None,
                "message": f"{what} isn't available right now.",
            }
        if index in state.fail_on:
            return {"available": True, "status": "FAILED", "text": None,
                    "message": f"{what} failed: {state.fail_on[index]}. Nothing was changed."}
        text = state.replies.pop(0) if state.replies else "Translated."
        return {"available": True, "status": "COMPLETED", "text": text, "provider": "anthropic",
                "model": "claude-sonnet-4-5", "responseModel": "claude-sonnet-4-5-20250929"}

    monkeypatch.setattr(ai, "_run_chat_verb", run_chat_verb)
    return state


# --------------------------------------------------------------------------------------------
# Settings
# --------------------------------------------------------------------------------------------


def test_the_capability_is_off_by_default():
    settings = build_settings({})
    assert settings.capability_enabled(Capability.TRANSCRIPT_INSIGHTS) is False
    assert settings.transcript_insights_provider == "auto"
    assert settings.transcript_insights_max_translate_chars == 96_000


def test_the_master_switch_alone_is_not_enough_and_neither_is_the_capability_switch():
    assert not build_settings({"AI_FEATURES_ENABLED": "true"}).capability_enabled(
        Capability.TRANSCRIPT_INSIGHTS
    )
    assert not build_settings({"AI_TRANSCRIPT_INSIGHTS_ENABLED": "true"}).capability_enabled(
        Capability.TRANSCRIPT_INSIGHTS
    )
    assert build_settings(ON).capability_enabled(Capability.TRANSCRIPT_INSIGHTS)


def test_the_probe_covers_every_capability_including_insights():
    report = probe(build_settings(ON))
    names = {item["capability"] for item in report["capabilities"]}
    assert names == {str(c) for c in Capability}
    insights = next(i for i in report["capabilities"] if i["capability"] == "transcript_insights")
    assert insights["available"] is True and insights["provider"] == "text_verbs"
    assert report["limits"]["transcriptInsightsMaxTranslateChars"] == 96_000


@pytest.mark.parametrize("raw", ["lots", "100"])
def test_a_bad_max_characters_value_falls_back_to_the_default_with_a_note(raw):
    settings = build_settings({**ON, "AI_TRANSCRIPT_INSIGHTS_MAX_TRANSLATE_CHARS": raw})
    assert settings.transcript_insights_max_translate_chars == 96_000
    assert any("AI_TRANSCRIPT_INSIGHTS_MAX_TRANSLATE_CHARS" in note for note in settings.notes)
    assert (
        build_settings(
            {"AI_TRANSCRIPT_INSIGHTS_MAX_TRANSLATE_CHARS": "5000"}
        ).transcript_insights_max_translate_chars
        == 5000
    )


# --------------------------------------------------------------------------------------------
# Sources
# --------------------------------------------------------------------------------------------


def test_select_sources_across_the_three_transcription_modes_and_an_edit():
    raw = Row(transcriptText=HINDI, transcriptSummary=HINDI, transcriptEditedAt=None)
    assert ti.select_sources(raw) == (HINDI.strip(), None)
    refined = Row(
        transcriptText="**Artisan:** " + HINDI, transcriptSummary=HINDI, transcriptEditedAt=None
    )
    assert ti.select_sources(refined) == (("**Artisan:** " + HINDI).strip(), HINDI.strip())
    translated = Row(transcriptText=ENGLISH, transcriptSummary=HINDI, transcriptEditedAt=None)
    assert ti.select_sources(translated) == (ENGLISH, HINDI.strip())
    edited = Row(
        transcriptText=ENGLISH, transcriptSummary=HINDI, transcriptEditedAt=datetime.now(UTC)
    )
    assert ti.select_sources(edited) == (ENGLISH, None)
    assert ti.select_sources(Row(transcriptText="  ", transcriptSummary=None)) == ("", None)


def test_source_hash_is_stable_and_sensitive():
    assert ti.source_hash("a", None) == ti.source_hash("a", "")
    assert ti.source_hash("a", "b") != ti.source_hash("ab", None)
    assert len(ti.source_hash("a", None)) == 64


# --------------------------------------------------------------------------------------------
# Parsing
# --------------------------------------------------------------------------------------------


def test_more_than_five_lines_is_trimmed_and_fenced_json_is_accepted():
    reply = (
        '```json\n{"primaryLanguage": "en", "summary": ["1","2","3","4","5","6","7"], '
        '"quotes": []}\n```'
    )
    parsed = ti.parse_insight(reply, [ENGLISH])
    assert parsed["summary"] == ["1", "2", "3", "4", "5"]
    assert parsed["primaryLanguage"] == "en"


def test_a_non_verbatim_quote_is_dropped_and_a_verbatim_one_survives_whitespace_and_case():
    reply = summary_reply(
        "en",
        quotes=[
            {"original": "the MUD is   soaked overnight.", "english": "x", "speaker": None},
            {"original": "We always use indigo from Jaipur.", "english": "x", "speaker": None},
        ],
    )
    parsed = ti.parse_insight(reply, [ENGLISH])
    assert [q["original"] for q in parsed["quotes"]] == ["the MUD is   soaked overnight."]


@pytest.mark.parametrize(
    "reply", [None, "", "no json here", "{broken", "[1, 2]", '{"summary": 5}']
)
def test_malformed_output_gives_an_empty_result_and_does_not_raise(reply):
    parsed = ti.parse_insight(reply, [ENGLISH])
    assert parsed["summary"] == [] and parsed["quotes"] == []


# --------------------------------------------------------------------------------------------
# Translation
# --------------------------------------------------------------------------------------------


def test_chunking_respects_paragraph_bounds():
    paragraphs = [(f"p{i} ") * 300 for i in range(10)]
    chunks = ti.chunk_paragraphs("\n\n".join(paragraphs), 3_000)
    assert all(len(c) <= 3_000 for c in chunks)
    for chunk in chunks:
        for part in chunk.split("\n\n"):
            assert part.strip() in [p.strip() for p in paragraphs]
    assert sum(len(c.split("\n\n")) for c in chunks) == 10
    assert [len(c) for c in ti.chunk_paragraphs("x" * 7_000, 3_000)] == [3_000, 3_000, 1_000]


def test_translation_past_the_cap_is_partial(fake, provider, monkeypatch):
    monkeypatch.setattr(ti, "max_translate_chars", lambda: 4_000)
    result = asyncio.run(ti.translate("\n\n".join(["क" * 3_000] * 3), None))
    assert result["status"] == ti.PARTIAL
    assert result["note"] == "Translated the first 3,000 characters."
    assert len(provider.calls) == 1


def test_english_input_is_not_needed_and_makes_no_second_call(fake, provider):
    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    provider.replies = [summary_reply("en", quotes=[])]
    assert asyncio.run(ti.generate(media, user_id=None))["status"] == "COMPLETED"
    assert len(provider.calls) == 1
    row = fake.transcriptinsight.rows[0]
    assert row.translationStatus == ti.NOT_NEEDED
    assert row.translationNote == "The transcript is already in English."


def test_a_failed_chunk_fails_the_translation_and_the_summary_is_still_stored(fake, provider):
    media = fake.add_media(transcriptText=HINDI, transcriptStatus="COMPLETED")
    provider.replies = [summary_reply()]
    provider.fail_on = {2: "the service had a problem"}
    asyncio.run(ti.generate(media, user_id=None))
    row = fake.transcriptinsight.rows[0]
    assert row.translationStatus == ti.FAILED and row.translationText is None
    assert row.summaryLines[0] == "Dabu printing is done in the village."
    assert row.provider == "anthropic" and row.modelId == "claude-sonnet-4-5"
    assert row.modelVersion == "claude-sonnet-4-5-20250929"
    assert row.promptVersion == "transcript-insights/1"


def test_a_non_english_transcript_is_translated_at_the_designed_temperatures(fake, provider):
    from app.services.ai_providers import AiTask

    media = fake.add_media(transcriptText=HINDI, transcriptStatus="COMPLETED")
    provider.replies = [summary_reply(), "We do dabu printing.\n\nThe mud is soaked overnight."]
    asyncio.run(ti.generate(media, user_id="usr_owner"))
    row = fake.transcriptinsight.rows[0]
    assert row.translationStatus == ti.COMPLETED and row.sourceLanguage == "hi"
    assert row.generated["translationText"] == row.translationText
    assert provider.calls == [
        (AiTask.SUMMARISE, "usr_owner", 0.2),
        (AiTask.TRANSLATE, "usr_owner", 0.1),
    ]


# --------------------------------------------------------------------------------------------
# Queue
# --------------------------------------------------------------------------------------------


def _completed_transcription(fake, media, text=ENGLISH):
    from app.services import media_queue

    job = Row(id="job_t", mediaFileId=media.id, jobType="TRANSCRIPTION")
    fake.mediaprocessingjob.rows.append(
        Row(id="job_t", mediaFileId=media.id, jobType="TRANSCRIPTION", status="PROCESSING",
            attempts=1, maxAttempts=3)
    )
    asyncio.run(
        media_queue._apply_transcription_result(job, {"status": "COMPLETED", "text": text})
    )


def _insight_jobs(fake, media_id=None):
    return [
        j for j in fake.mediaprocessingjob.rows
        if j.jobType == "TRANSCRIPT_INSIGHTS" and (media_id is None or j.mediaFileId == media_id)
    ]


def test_a_completed_transcription_queues_exactly_one_insights_job(fake):
    media = fake.add_media()
    _completed_transcription(fake, media)
    _completed_transcription(fake, media)
    jobs = _insight_jobs(fake)
    assert len(jobs) == 1
    assert jobs[0].requestedById is None and jobs[0].priority == 80
    assert jobs[0].status == "QUEUED"


def test_with_the_feature_off_nothing_is_queued_and_queued_jobs_are_not_drained(fake, monkeypatch):
    from app.services import media_queue

    monkeypatch.setattr(ti, "enabled", lambda: False)
    media = fake.add_media()
    _completed_transcription(fake, media)
    assert not _insight_jobs(fake)

    seen: dict[str, Any] = {}

    async def find_many(**kwargs):
        seen.update(kwargs)
        return []

    async def no_settings():
        # Unpaused: a missing row reads as paused (transcription service, 18 Oct 2026), and this
        # test is about the insights switch, not the pause.
        return {"transcriptionPaused": False}

    monkeypatch.setattr(fake.mediaprocessingjob, "find_many", find_many)
    monkeypatch.setattr(media_queue, "load_app_settings", no_settings)
    monkeypatch.setattr(media_queue, "within_processing_window", lambda _s: True)
    monkeypatch.setattr(media_queue, "recover_stale_processing_jobs", _noop)
    settings = SimpleNamespace(media_queue_batch_size=5)
    asyncio.run(media_queue.process_next_media_jobs(limit=5, settings=settings))
    assert seen["where"]["jobType"] == {"not": "TRANSCRIPT_INSIGHTS"}

    monkeypatch.setattr(media_queue, "within_processing_window", lambda _s: False)
    asyncio.run(media_queue.process_next_media_jobs(limit=5, settings=settings))
    assert seen["where"]["jobType"] == {"not_in": ["TRANSCRIPTION", "TRANSCRIPT_INSIGHTS"]}

    # On, insights jobs flow even outside the processing window.
    monkeypatch.setattr(ti, "enabled", lambda: True)
    asyncio.run(media_queue.process_next_media_jobs(limit=5, settings=settings))
    assert seen["where"]["jobType"] == {"not": "TRANSCRIPTION"}


def _insights_job(fake, media, requested_by=None, attempts=1):
    row = Row(id=f"job_{media.id}", mediaFileId=media.id, jobType="TRANSCRIPT_INSIGHTS",
              status="PROCESSING", attempts=attempts, maxAttempts=3, requestedById=requested_by,
              error=None)
    fake.mediaprocessingjob.rows.append(row)
    return Row(**{**row.__dict__, "mediaFile": media})


def test_an_insights_job_never_fetches_from_s3(fake, provider, monkeypatch):
    from app.services import media_queue

    fetched = []
    for name in ("get_object_bytes", "download_to_temp", "head_object"):
        monkeypatch.setattr(media_queue, name, lambda *a, **k: fetched.append(a) or b"")
    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    provider.replies = [summary_reply("en", quotes=[])]
    job = _insights_job(fake, media)
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    assert fetched == []
    assert fake.mediaprocessingjob.get(job.id).status == "COMPLETED"


def test_a_provider_429_retries_with_backoff(fake, provider):
    from app.services import media_queue

    provider.fail_on = {1: "the service was busy"}
    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    job = _insights_job(fake, media)
    with pytest.raises(RuntimeError) as caught:
        asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    assert "busy" in str(caught.value)
    asyncio.run(media_queue._handle_job_failure(job.id, caught.value))
    stored = fake.mediaprocessingjob.get(job.id)
    assert stored.status == "QUEUED" and stored.runAfter > datetime.now(UTC)
    assert "busy" in stored.error


def test_with_no_key_the_job_is_unavailable_and_spends_nothing(fake, provider):
    from app.services import media_queue

    provider.unavailable = True
    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    job = _insights_job(fake, media, requested_by="usr_owner")
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    stored = fake.mediaprocessingjob.get(job.id)
    assert stored.status == "FAILED" and stored.result == {"status": "UNAVAILABLE"}
    assert "AI key" in stored.error
    assert fake.spent == []


def test_an_empty_transcript_completes_the_job_as_empty(fake, provider):
    from app.services import media_queue

    media = fake.add_media(transcriptText="   ")
    job = _insights_job(fake, media)
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    stored = fake.mediaprocessingjob.get(job.id)
    assert stored.status == "COMPLETED" and stored.result == {"status": "EMPTY"}
    assert provider.calls == []


def test_regenerate_adds_a_version_supersedes_the_old_and_freezes_generated(fake, provider):
    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    provider.replies = [summary_reply("en", quotes=[]), summary_reply("en", quotes=[])]
    asyncio.run(ti.generate(media, user_id=None))
    first = fake.transcriptinsight.rows[0]
    first_generated = dict(first.generated)
    asyncio.run(ti.generate(media, user_id="usr_owner"))
    second = fake.transcriptinsight.rows[1]
    assert (first.version, second.version) == (1, 2)
    assert first.supersededAt is not None and second.supersededAt is None
    assert first.generated == first_generated
    assert second.requestedById == "usr_owner"
    assert asyncio.run(ti.current_insight(media.id)) is second


def test_an_automatic_job_does_not_replace_an_edited_insight(fake, provider):
    from app.services import media_queue

    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    provider.replies = [summary_reply("en", quotes=[])]
    asyncio.run(ti.generate(media, user_id=None))
    fake.transcriptinsight.rows[0].editedAt = datetime.now(UTC)
    job = _insights_job(fake, media)
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    assert len(fake.transcriptinsight.rows) == 1
    assert len(provider.calls) == 1


# --- designer: consent at send time and the daily allowance ------------------------------------


def test_consent_withdrawn_after_enqueue_sends_nothing_and_fails_with_the_insights_sentence(
    fake, provider
):
    from app.services import media_queue

    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    job = _insights_job(fake, media, requested_by="usr_owner")
    fake.consent = SendVerdict(SendDecision.REFUSED, workshop_id="dw_1", refusal=REFUSAL)
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    stored = fake.mediaprocessingjob.get(job.id)
    assert stored.status == "FAILED" and stored.error == REFUSAL
    assert "cannot be summarised there" in stored.error
    assert provider.calls == [] and fake.spent == []
    assert fake.consent_sends[-1] is dictation_consent.INSIGHTS
    # The transcript on the clip is untouched.
    assert fake.mediafile.get(media.id).transcriptText == ENGLISH
    assert fake.mediafile.get(media.id).transcriptStatus == "COMPLETED"


def test_a_requested_run_spends_the_requesters_allowance_once(fake, provider):
    from app.services import media_queue

    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    provider.replies = [summary_reply("en", quotes=[])]
    job = _insights_job(fake, media, requested_by="usr_owner")
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    assert fake.spent == [("usr_owner", "TRANSCRIPT_INSIGHTS")]


def test_automatic_runs_do_not_spend_an_allowance(fake, provider):
    from app.services import media_queue

    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    provider.replies = [summary_reply("en", quotes=[])]
    asyncio.run(media_queue._process_job(_insights_job(fake, media), SimpleNamespace()))
    assert fake.spent == []
    assert provider.calls[0][1] is None  # the deployment's key


def test_every_send_description_still_has_its_fields():
    for described in (*dictation_consent.SENDS.values(), dictation_consent.INSIGHTS):
        assert described.material and described.destination and described.consequence
    assert dictation_consent.INSIGHTS.alternative.endswith(" ")


# --------------------------------------------------------------------------------------------
# Routes
# --------------------------------------------------------------------------------------------


OWNER = SimpleNamespace(id="usr_owner", role="DESIGNER")
OTHER = SimpleNamespace(id="usr_other", role="DESIGNER")
VOLUNTEER = SimpleNamespace(id="usr_volunteer", role="CROWDSOURCE_VOLUNTEER")


def _client(user) -> TestClient:
    from app.api.routes import media as media_routes, transcript_insights as routes
    from app.core.deps import get_current_user

    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.include_router(media_routes.router, prefix="/api")
    app.dependency_overrides[get_current_user] = lambda: user
    return TestClient(app)


def _ready(fake, provider, text=ENGLISH, language="en", **columns):
    media = fake.add_media(transcriptText=text, transcriptStatus="COMPLETED", **columns)
    provider.replies = [summary_reply(language, quotes=[])]
    asyncio.run(ti.generate(media, user_id=None))
    return media


def test_with_the_flag_off_all_four_routes_are_404(fake, monkeypatch):
    monkeypatch.setattr(ti, "enabled", lambda: False)
    media = fake.add_media(transcriptText=ENGLISH)
    client = _client(OWNER)
    responses = [
        client.get(f"/api/media/{media.id}/insights"),
        client.get(f"/api/media/insights?ids={media.id}"),
        client.patch(f"/api/media/{media.id}/insights", json={"version": 1, "summaryLines": ["x"]}),
        client.post(f"/api/media/{media.id}/insights/regenerate", json={}),
    ]
    assert [r.status_code for r in responses] == [404] * 4
    assert all(r.json() == {"detail": "Not found"} for r in responses)


def test_get_returns_the_contract_shape(fake, provider):
    media = _ready(fake, provider)
    body = _client(OTHER).get(f"/api/media/{media.id}/insights").json()
    assert body["state"] == "READY" and body["stale"] is False
    assert body["canEdit"] is False and body["canRegenerate"] is False
    insight = body["insight"]
    assert insight["version"] == 1 and len(insight["summaryLines"]) == 3
    assert insight["translation"] == {
        "status": "NOT_NEEDED", "text": None, "note": "The transcript is already in English."
    }
    assert insight["provenance"]["promptVersion"] == "transcript-insights/1"
    assert insight["provenance"]["modelVersion"] == "claude-sonnet-4-5-20250929"
    assert insight["provenance"]["generatedAt"].endswith("Z")
    assert insight["edited"] is None


def test_states_queued_failed_no_transcript_and_not_generated(fake):
    client = _client(OWNER)
    empty = fake.add_media(transcriptText=None)
    assert client.get(f"/api/media/{empty.id}/insights").json()["state"] == "NO_TRANSCRIPT"
    fresh = fake.add_media(transcriptText=ENGLISH)
    assert client.get(f"/api/media/{fresh.id}/insights").json()["state"] == "NOT_GENERATED"
    fake.mediaprocessingjob.rows.append(
        Row(id="j1", mediaFileId=fresh.id, jobType="TRANSCRIPT_INSIGHTS", status="FAILED",
            error="The summary couldn't be read. Try again.",
            createdAt=datetime.now(UTC) - timedelta(minutes=5))
    )
    failed = client.get(f"/api/media/{fresh.id}/insights").json()
    assert failed["state"] == "FAILED"
    assert failed["error"] == "The summary couldn't be read. Try again."
    fake.mediaprocessingjob.rows.append(
        Row(id="j2", mediaFileId=fresh.id, jobType="TRANSCRIPT_INSIGHTS", status="QUEUED",
            createdAt=datetime.now(UTC))
    )
    assert client.get(f"/api/media/{fresh.id}/insights").json()["state"] == "QUEUED"


def test_a_non_uploader_non_admin_gets_403_on_patch_and_regenerate(fake, provider):
    media = _ready(fake, provider)
    client = _client(OTHER)
    assert client.patch(
        f"/api/media/{media.id}/insights", json={"version": 1, "summaryLines": ["x"]}
    ).status_code == 403
    assert client.post(f"/api/media/{media.id}/insights/regenerate", json={}).status_code == 403


def test_a_workshop_holder_is_refused_by_the_transcripts_own_rule(fake, provider, monkeypatch):
    from fastapi import HTTPException

    from app.services import design_workshop_posts

    async def refuse(_media, _user, **_k):
        raise HTTPException(status_code=403, detail="Inspectors read this and do not rewrite it.")

    monkeypatch.setattr(design_workshop_posts, "refuse_a_holders_media_write", refuse)
    media = _ready(fake, provider)
    client = _client(OWNER)
    assert client.patch(
        f"/api/media/{media.id}/insights", json={"version": 1, "summaryLines": ["x"]}
    ).status_code == 403
    assert client.post(f"/api/media/{media.id}/insights/regenerate", json={}).status_code == 403


def test_patch_stamps_the_server_and_refuses_a_client_stamp(fake, provider):
    media = _ready(fake, provider)
    client = _client(OWNER)
    before = datetime.now(UTC)
    response = client.patch(f"/api/media/{media.id}/insights", json={
        "version": 1, "summaryLines": ["One.", "", "Two."],
        "quotes": [{"original": "The mud is soaked overnight.", "english": "", "speaker": None}],
    })
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["insight"]["summaryLines"] == ["One.", "Two."]
    assert body["insight"]["edited"]["at"]
    row = fake.transcriptinsight.rows[0]
    assert row.editedById == "usr_owner" and row.editedAt >= before
    assert row.generated["summaryLines"][0] == "Dabu printing is done in the village."
    refused = client.patch(
        f"/api/media/{media.id}/insights",
        json={"version": 1, "summaryLines": ["x"], "editedAt": "2026-01-01T00:00:00Z"},
    )
    assert refused.status_code == 422


def test_patch_limits():
    from app.schemas.transcript_insights import TranscriptInsightPatch

    with pytest.raises(ValidationError):
        TranscriptInsightPatch(version=1, summaryLines=["a"] * 6)
    with pytest.raises(ValidationError):
        TranscriptInsightPatch(version=1, summaryLines=["x" * 401])
    with pytest.raises(ValidationError):
        TranscriptInsightPatch(version=1, summaryLines=["", "  "])
    with pytest.raises(ValidationError):
        TranscriptInsightPatch(version=1, quotes=[{"original": "a", "english": "b"}] * 9)
    with pytest.raises(ValidationError):
        TranscriptInsightPatch(version=1, translationText="x" * 200_001)
    with pytest.raises(ValidationError):
        TranscriptInsightPatch(version=1)


def test_a_stale_version_is_a_409_version_conflict(fake, provider):
    media = _ready(fake, provider)
    response = _client(OWNER).patch(
        f"/api/media/{media.id}/insights", json={"version": 7, "summaryLines": ["x"]}
    )
    assert response.status_code == 409
    assert response.json() == {
        "detail": "This summary changed since you opened it.", "code": "VERSION_CONFLICT"
    }


def test_patch_without_a_current_row_is_404(fake):
    media = fake.add_media(transcriptText=ENGLISH)
    assert _client(OWNER).patch(
        f"/api/media/{media.id}/insights", json={"version": 1, "summaryLines": ["x"]}
    ).status_code == 404


def test_regenerating_an_edited_insight_needs_discard_edits(fake, provider):
    media = _ready(fake, provider)
    fake.transcriptinsight.rows[0].editedAt = datetime.now(UTC)
    client = _client(OWNER)
    refused = client.post(
        f"/api/media/{media.id}/insights/regenerate", json={"discardEdits": False}
    )
    assert refused.status_code == 409
    assert refused.json() == {
        "detail": "Regenerating replaces your edits.", "code": "EDITS_WOULD_BE_REPLACED"
    }
    accepted = client.post(
        f"/api/media/{media.id}/insights/regenerate", json={"discardEdits": True}
    )
    assert accepted.status_code == 202 and accepted.json()["state"] == "QUEUED"
    jobs = _insight_jobs(fake)
    assert len(jobs) == 1 and jobs[0].requestedById == "usr_owner"
    again = client.post(f"/api/media/{media.id}/insights/regenerate", json={"discardEdits": True})
    assert again.status_code == 202
    assert len(_insight_jobs(fake)) == 1
    # The allowance is spent by the worker when the run reaches a provider, never at enqueue.
    assert fake.spent == []


def test_regenerate_with_no_transcript_is_422(fake):
    media = fake.add_media(transcriptText="")
    response = _client(OWNER).post(f"/api/media/{media.id}/insights/regenerate", json={})
    assert response.status_code == 422
    assert response.json() == {"detail": "There is no transcript to summarise yet."}


def test_regenerate_with_a_cap_of_zero_is_429(fake, provider):
    media = _ready(fake, provider)
    fake.cap.limit = 0
    response = _client(OWNER).post(f"/api/media/{media.id}/insights/regenerate", json={})
    assert response.status_code == 429
    assert "switched off" in response.json()["detail"]
    assert not _insight_jobs(fake)


def test_regenerate_without_consent_is_409_with_the_gate_sentence(fake, provider):
    media = _ready(fake, provider)
    fake.consent = SendVerdict(SendDecision.REFUSED, workshop_id="dw_1", refusal=REFUSAL)
    response = _client(OWNER).post(f"/api/media/{media.id}/insights/regenerate", json={})
    assert response.status_code == 409
    assert response.json()["detail"] == REFUSAL
    assert not _insight_jobs(fake)


def test_the_batch_caps_at_50_and_leaves_out_unknown_ids(fake, provider):
    media = _ready(fake, provider)
    client = _client(OTHER)
    ids = ",".join(f"m{i}" for i in range(51))
    assert client.get(f"/api/media/insights?ids={ids}").status_code == 422
    body = client.get(f"/api/media/insights?ids={media.id},nope").json()
    assert list(body["items"]) == [media.id]
    assert body["items"][media.id]["insight"]["translation"]["text"] is None


def test_the_batch_route_is_not_swallowed_by_the_media_id_route(fake):
    response = _client(OWNER).get("/api/media/insights?ids=")
    assert response.status_code == 200 and response.json() == {"items": {}}


def test_the_app_registers_the_insights_router_before_media():
    # Read off the source: FastAPI now mounts included routers lazily, so api_router.routes no
    # longer lists their paths, and the match order is the include order.
    from pathlib import Path

    source = (Path(__file__).resolve().parents[1] / "app" / "api" / "router.py").read_text(
        encoding="utf-8"
    )
    insights = source.index("api_router.include_router(transcript_insights.router)")
    single = source.index("api_router.include_router(media.router)")
    assert insights < single


# --- designer: an insight is a copy of the transcript -------------------------------------------


def test_a_viewer_who_cannot_read_the_transcript_gets_404_and_is_left_out_of_the_batch(
    fake, provider
):
    theirs = _ready(fake, provider, uploadedById="usr_owner")
    mine = _ready(fake, provider, uploadedById="usr_volunteer")
    # A volunteer may take their own uploads and nothing else: an untagged file of another uploader
    # loses its transcript in public_encode, so its insight does not travel either.
    fake.scope = ({"usr_volunteer"}, frozenset())
    client = _client(VOLUNTEER)
    assert client.get(f"/api/media/{theirs.id}/insights").status_code == 404
    assert client.get(f"/api/media/{mine.id}/insights").status_code == 200
    batch = client.get(f"/api/media/insights?ids={theirs.id},{mine.id}").json()
    assert list(batch["items"]) == [mine.id]
    assert client.post(
        f"/api/media/{theirs.id}/insights/regenerate", json={}
    ).status_code == 404


def test_a_workshop_tagged_file_travels_to_a_viewer_who_may_open_that_workshop(fake, provider):
    tagged = _ready(
        fake, provider, uploadedById="usr_owner", linkedRecordType="designWorkshop",
        linkedRecordId="dw_1",
    )
    fake.scope = ({"usr_volunteer"}, frozenset({"dw_1"}))
    assert _client(VOLUNTEER).get(f"/api/media/{tagged.id}/insights").status_code == 200


def test_editing_the_transcript_requeues_an_unedited_insight_and_only_marks_an_edited_one_stale(
    fake, provider, monkeypatch
):
    from app.api.routes import media as media_routes

    async def _public(row, _viewer=None, **_kwargs):
        return {"id": row.id}

    monkeypatch.setattr(media_routes, "db", fake)
    monkeypatch.setattr(media_routes, "_public", _public)

    async def require_record(_delegate, record_id):
        return fake.mediafile.get(record_id)

    monkeypatch.setattr(media_routes, "require_record", require_record)
    media = _ready(fake, provider)
    asyncio.run(
        media_routes.set_media_transcript(media.id, SimpleNamespace(text="New words."), OWNER)
    )
    assert len(_insight_jobs(fake, media.id)) == 1

    other = _ready(fake, provider)
    fake.transcriptinsight.rows[-1].editedAt = datetime.now(UTC)
    asyncio.run(
        media_routes.set_media_transcript(other.id, SimpleNamespace(text="Changed."), OWNER)
    )
    assert not _insight_jobs(fake, other.id)
    assert _client(OWNER).get(f"/api/media/{other.id}/insights").json()["stale"] is True


# --------------------------------------------------------------------------------------------
# Backfill
# --------------------------------------------------------------------------------------------


def _backfill(fake, monkeypatch):
    from scripts import backfill_transcript_insights as backfill

    monkeypatch.setattr(backfill, "db", fake)
    monkeypatch.setattr(backfill.insights, "db", fake)
    return backfill


def test_a_dry_run_writes_nothing(fake, monkeypatch, capsys):
    backfill = _backfill(fake, monkeypatch)
    fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    fake.add_media(transcriptText=HINDI, transcriptStatus="COMPLETED")
    fake.add_media(transcriptText=None, transcriptStatus="FAILED")
    before = len(fake.writes)
    assert asyncio.run(backfill.run([])) == 0
    assert len(fake.writes) == before
    out = capsys.readouterr().out
    assert "DRY RUN" in out and "eligible=2" in out and "would_queue=2" in out
    assert f"characters={len(ENGLISH) + len(HINDI.strip())}" in out


def test_execute_with_the_feature_off_exits_2(fake, monkeypatch, capsys):
    backfill = _backfill(fake, monkeypatch)
    monkeypatch.setattr(ti, "enabled", lambda: False)
    assert asyncio.run(backfill.run(["--execute"])) == 2
    assert (
        "Turn on AI_FEATURES_ENABLED and AI_TRANSCRIPT_INSIGHTS_ENABLED first."
        in capsys.readouterr().out
    )


def test_execute_queues_only_eligible_media_at_priority_90_and_is_idempotent(
    fake, provider, monkeypatch
):
    backfill = _backfill(fake, monkeypatch)
    wanted = fake.add_media(transcriptText=HINDI, transcriptStatus="EMPTY")
    done = _ready(fake, provider)
    fake.add_media(transcriptText=ENGLISH, transcriptStatus="QUEUED")
    assert asyncio.run(backfill.run(["--execute"])) == 0
    jobs = _insight_jobs(fake)
    assert [(j.mediaFileId, j.priority, j.requestedById) for j in jobs] == [(wanted.id, 90, None)]
    assert asyncio.run(backfill.run(["--execute"])) == 0
    assert len(_insight_jobs(fake)) == 1
    done.transcriptText = "Something else entirely."
    assert asyncio.run(backfill.run(["--execute", "--include-stale"])) == 0
    assert {j.mediaFileId for j in _insight_jobs(fake)} == {wanted.id, done.id}


# --------------------------------------------------------------------------------------------
# Verifier findings, 2026-10-18
# --------------------------------------------------------------------------------------------


def _automatic_job(fake, media, status="QUEUED"):
    row = Row(id=f"auto_{media.id}", mediaFileId=media.id, jobType="TRANSCRIPT_INSIGHTS",
              status=status, attempts=0, maxAttempts=3, requestedById=None, error=None,
              createdAt=datetime.now(UTC))
    fake.mediaprocessingjob.rows.append(row)
    return row


@pytest.mark.parametrize("status", ["QUEUED", "PROCESSING"])
def test_regenerate_on_an_edited_summary_claims_the_active_automatic_job(fake, provider, status):
    from app.services import media_queue

    media = _ready(fake, provider)
    fake.transcriptinsight.rows[0].editedAt = datetime.now(UTC)
    auto = _automatic_job(fake, media, status)
    client = _client(OWNER)
    refused = client.post(f"/api/media/{media.id}/insights/regenerate", json={"discardEdits": False})
    assert refused.status_code == 409 and refused.json()["code"] == "EDITS_WOULD_BE_REPLACED"
    assert auto.requestedById is None

    accepted = client.post(f"/api/media/{media.id}/insights/regenerate", json={"discardEdits": True})
    assert accepted.status_code == 202 and accepted.json()["state"] == status
    jobs = [j for j in fake.mediaprocessingjob.rows if j.jobType == "TRANSCRIPT_INSIGHTS"]
    assert jobs == [auto] and auto.requestedById == "usr_owner"

    # The worker reads the requester fresh, so the claimed job runs instead of being skipped, on
    # the requester's key and allowance.
    provider.replies = [summary_reply("en", quotes=[])]
    asyncio.run(media_queue._process_job(Row(**{**auto.__dict__, "requestedById": None,
                                                "mediaFile": media}), SimpleNamespace()))
    assert auto.status == "COMPLETED" and auto.result["status"] == "COMPLETED"
    current = asyncio.run(ti.current_insight(media.id))
    assert current.version == 2 and current.editedAt is None
    assert provider.calls[-1][1] == "usr_owner"
    assert fake.spent == [("usr_owner", "TRANSCRIPT_INSIGHTS")]


def test_claiming_an_automatic_job_still_checks_consent_and_the_allowance(fake, provider):
    media = _ready(fake, provider)
    fake.transcriptinsight.rows[0].editedAt = datetime.now(UTC)
    auto = _automatic_job(fake, media)
    client = _client(OWNER)
    fake.cap.limit, fake.cap.used = 1, 1
    spent = client.post(f"/api/media/{media.id}/insights/regenerate", json={"discardEdits": True})
    assert spent.status_code == 429 and auto.requestedById is None
    fake.cap.limit = None
    fake.consent = SendVerdict(SendDecision.REFUSED, workshop_id="dw_1", refusal=REFUSAL)
    refused = client.post(f"/api/media/{media.id}/insights/regenerate", json={"discardEdits": True})
    assert refused.status_code == 409 and auto.requestedById is None


def test_regenerate_while_a_requested_job_is_active_queues_nothing_more(fake, provider):
    media = _ready(fake, provider)
    fake.transcriptinsight.rows[0].editedAt = datetime.now(UTC)
    asked = _automatic_job(fake, media)
    asked.requestedById = "usr_admin"
    response = _client(OWNER).post(f"/api/media/{media.id}/insights/regenerate", json={})
    assert response.status_code == 202 and response.json()["state"] == "QUEUED"
    assert asked.requestedById == "usr_admin"
    assert len([j for j in fake.mediaprocessingjob.rows if j.jobType == "TRANSCRIPT_INSIGHTS"]) == 1


@pytest.mark.parametrize(
    ("fault", "sentence"),
    [
        ("the service did not accept the key", ti.KEY_REFUSED),
        ("the service turned the request down", ti.REQUEST_REFUSED),
        ("the file was too large for the service", ti.REQUEST_REFUSED),
    ],
)
def test_a_refused_key_or_request_spends_once_and_closes_the_job(fake, provider, fault, sentence):
    from app.services import media_queue

    provider.fail_on = {1: fault}
    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    job = _insights_job(fake, media, requested_by="usr_owner")
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))  # does not raise: no retry
    stored = fake.mediaprocessingjob.get(job.id)
    assert stored.status == "FAILED" and stored.error == sentence
    assert fake.spent == [("usr_owner", "TRANSCRIPT_INSIGHTS")]
    assert len(provider.calls) == 1


def test_a_declined_summary_is_not_retried(fake, provider, monkeypatch):
    from app.services import ai, media_queue

    async def refused(**_kwargs):
        return {"available": True, "status": "REFUSED", "text": None, "message": "declined"}

    monkeypatch.setattr(ai, "_run_chat_verb", refused)
    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    job = _insights_job(fake, media, requested_by="usr_owner")
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    stored = fake.mediaprocessingjob.get(job.id)
    assert stored.status == "FAILED" and stored.error == ti.MODEL_DECLINED
    assert fake.spent == [("usr_owner", "TRANSCRIPT_INSIGHTS")]


@pytest.mark.parametrize("fault", ["the service was busy", "the service had a problem",
                                   "the service could not be reached"])
def test_a_retried_failure_spends_nothing(fake, provider, fault):
    from app.services import media_queue

    provider.fail_on = {1: fault}
    media = fake.add_media(transcriptText=ENGLISH, transcriptStatus="COMPLETED")
    job = _insights_job(fake, media, requested_by="usr_owner")
    with pytest.raises(RuntimeError):
        asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    assert fake.spent == []


def test_the_failed_translation_note_names_no_action_a_reader_may_not_have():
    assert ti.TRANSLATION_FAILED_NOTE == "The translation couldn't be finished."
    assert "egenerate" not in ti.TRANSLATION_FAILED_NOTE
