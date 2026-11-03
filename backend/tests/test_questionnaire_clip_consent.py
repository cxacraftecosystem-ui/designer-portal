from types import SimpleNamespace

import pytest

from app.services import dictation_consent
from app.services.dictation_consent import (
    DictationConsent,
    SendDecision,
    interview_workshop_id,
    transcription_verdict,
)


class _FakeDb:
    def __init__(self, interviews: dict, workshops: dict):
        self.interview_lookups: list[str] = []
        self.workshop_lookups: list[str] = []
        self.questionnaireinterview = SimpleNamespace(find_unique=self._interview)
        self.designworkshop = SimpleNamespace(find_unique=self._workshop)
        self._interviews = interviews
        self._workshops = workshops

    async def _interview(self, where):
        self.interview_lookups.append(where["id"])
        return self._interviews.get(where["id"])

    async def query_raw(self, *_args):
        return []

    async def _workshop(self, where):
        self.workshop_lookups.append(where["id"])
        return self._workshops.get(where["id"])


def _install(monkeypatch, *, interviews=None, workshops=None) -> _FakeDb:
    fake = _FakeDb(interviews or {}, workshops or {})
    monkeypatch.setattr(dictation_consent, "db", fake)
    return fake


def _clip(**overrides):
    row = {
        "id": "media-1",
        "linkedRecordType": "questionnaire",
        "linkedRecordId": "interview-1",
        "questionnaireInterviewId": "interview-1",
    }
    row.update(overrides)
    return row


def _interview(design_workshop_id):
    return SimpleNamespace(id="interview-1", designWorkshopId=design_workshop_id)


def _workshop(consent):
    return SimpleNamespace(id="ws-1", dictationConsent=consent)



async def test_an_interview_that_names_no_workshop_is_exactly_as_ungated_as_before(monkeypatch):
    _install(monkeypatch, interviews={"interview-1": _interview(None)})
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.NOT_WORKSHOP_MATERIAL
    assert verdict.may_send is True
    assert verdict.refusal is None


async def test_an_interview_this_server_cannot_find_is_an_orphan_and_not_a_refusal(monkeypatch):
    _install(monkeypatch, interviews={})
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.NOT_WORKSHOP_MATERIAL
    assert verdict.may_send is True


async def test_a_row_that_is_not_questionnaire_material_never_pays_for_the_lookup(monkeypatch):
    fake = _install(monkeypatch, interviews={"interview-1": _interview("ws-1")})
    portrait = {"id": "media-2", "linkedRecordType": "artisan", "linkedRecordId": "artisan-1"}
    verdict = await transcription_verdict(portrait)
    assert verdict.decision is SendDecision.NOT_WORKSHOP_MATERIAL
    assert fake.interview_lookups == []



async def test_an_interview_that_names_a_refused_workshop_refuses_the_queue_too(monkeypatch):
    _install(
        monkeypatch,
        interviews={"interview-1": _interview("ws-1")},
        workshops={"ws-1": _workshop(DictationConsent.REFUSED)},
    )
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.REFUSED
    assert verdict.may_send is False
    assert verdict.refusal


async def test_an_interview_whose_workshop_never_answered_is_sent(monkeypatch):
    _install(
        monkeypatch,
        interviews={"interview-1": _interview("ws-1")},
        workshops={"ws-1": _workshop(DictationConsent.NOT_RECORDED)},
    )
    verdict = await transcription_verdict(_clip())
    assert verdict.may_send is True
    assert verdict.refusal is None


async def test_a_granted_workshop_permits_the_send_and_names_the_workshop(monkeypatch):
    _install(
        monkeypatch,
        interviews={"interview-1": _interview("ws-1")},
        workshops={"ws-1": _workshop(DictationConsent.GRANTED)},
    )
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.GRANTED
    assert verdict.may_send is True
    assert verdict.workshop_id == "ws-1"


async def test_a_workshop_that_no_longer_exists_holds_no_refusal_and_is_sent(monkeypatch):
    _install(monkeypatch, interviews={"interview-1": _interview("ws-1")}, workshops={})
    verdict = await transcription_verdict(_clip())
    assert verdict.may_send is True


async def test_a_workshop_whose_consent_cannot_be_read_refuses(monkeypatch):
    fake = _install(monkeypatch, interviews={"interview-1": _interview("ws-1")}, workshops={})

    async def _broken(*_a, **_k):
        raise RuntimeError("database blinked")

    fake.designworkshop = SimpleNamespace(find_unique=_broken)
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.REFUSED
    assert verdict.refusal == dictation_consent.UNREADABLE



async def test_the_typed_foreign_key_is_read_before_the_free_text_tag(monkeypatch):
    fake = _install(monkeypatch, interviews={"interview-fk": _interview(None)})
    await interview_workshop_id(
        _clip(questionnaireInterviewId="interview-fk", linkedRecordId="interview-tag")
    )
    assert fake.interview_lookups == ["interview-fk"]


async def test_the_tag_is_the_fallback_for_a_row_that_carries_no_foreign_key(monkeypatch):
    fake = _install(monkeypatch, interviews={"interview-1": _interview(None)})
    await interview_workshop_id(_clip(questionnaireInterviewId=None))
    assert fake.interview_lookups == ["interview-1"]


@pytest.mark.parametrize("spelling", ["questionnaire", "Questionnaire", " QUESTIONNAIRE "])
async def test_the_tag_is_matched_loosely_because_nothing_normalises_it(monkeypatch, spelling):
    fake = _install(monkeypatch, interviews={"interview-1": _interview("ws-1")})
    assert (
        await interview_workshop_id(
            _clip(questionnaireInterviewId=None, linkedRecordType=spelling)
        )
        == "ws-1"
    )
    assert fake.interview_lookups == ["interview-1"]


async def test_a_failed_read_raises_rather_than_reading_as_no_workshop(monkeypatch):
    class _Broken:
        questionnaireinterview = SimpleNamespace(
            find_unique=lambda where: (_ for _ in ()).throw(RuntimeError("the database blinked"))
        )

    monkeypatch.setattr(dictation_consent, "db", _Broken())
    with pytest.raises(RuntimeError):
        await interview_workshop_id(_clip())



async def test_one_refusal_among_several_named_workshops_is_the_answer(monkeypatch):
    _install(
        monkeypatch,
        interviews={"interview-1": _interview("ws-refused")},
        workshops={
            "ws-granted": _workshop(DictationConsent.GRANTED),
            "ws-refused": _workshop(DictationConsent.REFUSED),
        },
    )
    clip = _clip(linkedRecordType="designWorkshop", linkedRecordId="ws-granted")
    verdict = await transcription_verdict(clip)
    assert verdict.decision is SendDecision.REFUSED
