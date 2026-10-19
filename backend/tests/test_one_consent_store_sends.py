"""Every outside send asks the one consent store, and a withdrawn consent stops each of them.

The rule (``app/transcription/ledger.py``'s ``decide``, the same function documentation-portal's gate
calls) over this portal's subjects (``services/dictation_consent.py``):

* the recording's own row is read FIRST, and a refusal there always refuses, workshop or not;
* the subjects are every workshop that names the file plus every artisan of it with an answer on
  record (the artisan's own row, the record both portals read and write);
* a recording with no workshop and no row at all is sent as before (a designer's own dictation).

No database and no provider: ``dictation_consent.db`` is ``consent_store_fakes.LedgerDb``.
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest
from consent_store_fakes import LedgerDb, workshop
from fastapi import HTTPException

from app.services import dictation_consent
from app.services.dictation_consent import SendDecision, transcription_verdict

pytestmark = pytest.mark.anyio


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture
def ledger_db(monkeypatch) -> LedgerDb:
    fake = LedgerDb()
    monkeypatch.setattr(dictation_consent, "db", fake)
    return fake


def _dictation(**extra: Any) -> dict[str, Any]:
    """A designer's own recording: no workshop tag, no interview."""
    return {"id": "m1", "linkedRecordType": None, "linkedRecordId": None, **extra}


# ── the recording's own row ───────────────────────────────────────────────────────────────────


async def test_a_recording_with_no_workshop_and_no_row_is_sent_as_before(ledger_db):
    verdict = await transcription_verdict(_dictation())
    assert verdict.decision is SendDecision.NOT_WORKSHOP_MATERIAL and verdict.may_send


async def test_a_refusal_recorded_for_the_recording_stops_it_even_with_no_workshop(ledger_db):
    ledger_db.rows["RECORDING"]["m1"] = "REFUSED"
    verdict = await transcription_verdict(_dictation())
    assert verdict.decision is SendDecision.REFUSED and not verdict.may_send
    assert verdict.refusal and "refused" in verdict.refusal


async def test_a_refusal_for_the_recording_beats_a_granted_workshop(ledger_db):
    ledger_db.workshops["ws-1"] = workshop("ws-1", "GRANTED")
    ledger_db.rows["WORKSHOP"]["ws-1"] = "GRANTED"
    ledger_db.rows["RECORDING"]["m1"] = "REFUSED"
    clip = _dictation(linkedRecordType="designWorkshop", linkedRecordId="ws-1")
    verdict = await transcription_verdict(clip)
    assert not verdict.may_send


async def test_a_granted_recording_row_alone_lets_it_go(ledger_db):
    ledger_db.rows["RECORDING"]["m1"] = "GRANTED"
    verdict = await transcription_verdict(_dictation())
    assert verdict.decision is SendDecision.GRANTED and verdict.may_send


# ── the artisan's own row ─────────────────────────────────────────────────────────────────────


async def test_a_withdrawn_artisan_consent_stops_a_recording_of_them(ledger_db):
    ledger_db.media_artisans["m1"] = ["a1"]
    ledger_db.rows["ARTISAN"]["a1"] = "GRANTED"
    assert (await transcription_verdict(_dictation(artisanId="a1"))).may_send
    ledger_db.rows["ARTISAN"]["a1"] = "REFUSED"
    verdict = await transcription_verdict(_dictation(artisanId="a1"))
    assert verdict.decision is SendDecision.REFUSED and verdict.refusal


async def test_an_artisan_refusal_stops_a_granted_workshops_recording(ledger_db):
    ledger_db.workshops["ws-1"] = workshop("ws-1", "GRANTED")
    ledger_db.rows["WORKSHOP"]["ws-1"] = "GRANTED"
    ledger_db.media_artisans["m1"] = ["a1", "a2"]
    clip = _dictation(linkedRecordType="designWorkshop", linkedRecordId="ws-1")
    assert (await transcription_verdict(clip)).may_send, "an artisan with no row: the workshop speaks"
    ledger_db.rows["ARTISAN"]["a2"] = "REFUSED"
    assert not (await transcription_verdict(clip)).may_send


async def test_the_insights_and_drafting_sends_ask_the_same_rule(ledger_db):
    ledger_db.media_artisans["m1"] = ["a1"]
    ledger_db.rows["ARTISAN"]["a1"] = "REFUSED"
    for send in (dictation_consent.INSIGHTS, dictation_consent.DRAFTING, dictation_consent.REFINEMENT):
        verdict = await transcription_verdict(_dictation(), send=send, resolve_from_stages=True)
        assert not verdict.may_send and send.destination in verdict.refusal


# ── answer drafting: POST /questionnaire/interviews/{id}/drafts ──────────────────────────────


@pytest.fixture
def drafting(monkeypatch, ledger_db):
    from app.api.routes import answer_drafts as routes

    interview = SimpleNamespace(id="i1", createdById="u1", designWorkshopId=None)
    media = SimpleNamespace(id="m1", questionnaireInterviewId="i1", transcriptText="We learned from my mother.",
                            linkedRecordType="questionnaire", linkedRecordId="i1", artisanId=None)
    ledger_db.interviews["i1"] = interview
    ledger_db.participants["i1"] = ["a1"]
    ledger_db.media_artisans["m1"] = ["a1"]
    ledger_db.rows["ARTISAN"]["a1"] = "GRANTED"
    asked: list[Any] = []

    async def editable(_interview_id, _user):
        return interview

    async def section(_section_id):
        return SimpleNamespace(id="s1")

    class _Table:
        def __init__(self, rows):
            self.rows = rows

        async def find_unique(self, where):
            return next((r for r in self.rows if r.id == where["id"]), None)

        async def find_many(self, **_kwargs):
            return list(self.rows)

        async def upsert(self, where, data):
            return SimpleNamespace(**data["create"])

    fake = SimpleNamespace(
        mediafile=_Table([media]),
        questionnairequestion=_Table([SimpleNamespace(id="q1", prompt="History?")]),
        questionnaireresponse=_Table([]),
        questionnaireanswerdraft=_Table([]),
    )

    async def fake_draft(questions, transcript, settings, *, user_id):
        asked.append(transcript)
        return {1: "Since my grandfather"}, "anthropic", "claude-sonnet-5"

    monkeypatch.setattr(routes, "db", fake)
    monkeypatch.setattr(routes, "get_settings", lambda: SimpleNamespace(ai_answer_drafts_enabled=True))
    monkeypatch.setattr(routes, "_editable_interview", editable)
    monkeypatch.setattr(routes, "_section_or_404", section)
    monkeypatch.setattr(routes, "public_encode", lambda rows: [])
    monkeypatch.setattr(routes.ad, "draft_answers", fake_draft)
    return SimpleNamespace(routes=routes, asked=asked, ledger=ledger_db)


USER = SimpleNamespace(id="u1", role="RESEARCHER")


async def test_a_withdrawn_consent_stops_a_draft_from_a_recording(drafting):
    routes = drafting.routes
    out = await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    assert out["drafted"] == 1 and drafting.asked == ["We learned from my mother."]
    drafting.asked.clear()
    drafting.ledger.rows["ARTISAN"]["a1"] = "REFUSED"
    with pytest.raises(HTTPException) as exc:
        await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    assert exc.value.status_code == 409 and exc.value.detail
    assert drafting.asked == []


async def test_a_withdrawn_consent_stops_a_draft_from_pasted_text(drafting):
    routes = drafting.routes
    pasted = routes.DraftIn(sectionId="s1", transcript="Typed out from my notes.")
    assert (await routes.draft_section("i1", pasted, USER))["drafted"] == 1
    drafting.asked.clear()
    drafting.ledger.rows["ARTISAN"]["a1"] = "REFUSED"
    with pytest.raises(HTTPException) as exc:
        await routes.draft_section("i1", pasted, USER)
    assert exc.value.status_code == 409
    assert drafting.asked == []


async def test_a_refusal_for_the_recording_stops_its_draft(drafting):
    routes = drafting.routes
    drafting.ledger.rows["RECORDING"]["m1"] = "REFUSED"
    with pytest.raises(HTTPException) as exc:
        await routes.draft_section("i1", routes.DraftIn(sectionId="s1", mediaId="m1"), USER)
    assert exc.value.status_code == 409
    assert drafting.asked == []


# ── the artisan's own record: written here, read by both portals ──────────────────────────────


async def test_the_artisan_consent_route_appends_an_artisan_row_and_reads_it_back(monkeypatch, ledger_db):
    from app.api.routes import artisans as routes

    artisan = SimpleNamespace(id="a1", createdById="u1", designWorkshopId=None)

    async def required(_model, _record_id):
        return artisan

    async def not_a_post_holder(*_a, **_k):
        return None

    monkeypatch.setattr(routes, "require_record", required)
    monkeypatch.setattr(routes, "assert_may_write_a_record_filed_under", not_a_post_holder)
    assert (await routes.get_artisan_consent("a1", USER))["state"] == "NOT_RECORDED"
    out = await routes.record_artisan_consent("a1", routes.ArtisanConsentIn(state="REFUSED"), USER)
    assert out["state"] == "REFUSED"
    (args,) = ledger_db.appended
    assert args[1:5] == ("ARTISAN", "a1", "EXTERNAL_PROCESSING", "REFUSED")
    # And the row it wrote is what the gate reads next.
    ledger_db.media_artisans["m1"] = ["a1"]
    assert not (await transcription_verdict(_dictation(artisanId="a1"))).may_send


async def test_a_stranger_cannot_record_an_artisans_consent(monkeypatch, ledger_db):
    from app.api.routes import artisans as routes

    artisan = SimpleNamespace(id="a1", createdById="someone-else", designWorkshopId=None)

    async def required(_model, _record_id):
        return artisan

    async def nothing(*_a, **_k):
        return None

    async def not_above(*_a, **_k):
        return False

    import app.core.deps as deps

    monkeypatch.setattr(routes, "require_record", required)
    monkeypatch.setattr(routes, "assert_may_write_a_record_filed_under", nothing)
    monkeypatch.setattr(deps, "may_edit_lower_ranked_record", not_above)
    with pytest.raises(HTTPException) as exc:
        await routes.record_artisan_consent("a1", routes.ArtisanConsentIn(state="GRANTED"), USER)
    assert exc.value.status_code == 403
    assert ledger_db.appended == []
