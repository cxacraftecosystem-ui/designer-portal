"""A questionnaire voice note, and which workshop's consent — if any — governs sending it.

WHY THIS FILE EXISTS. On 2026-08-31 the questionnaire page began posting a just-recorded voice note
to ``POST /design-workshops/{id}/dictate`` for an immediate transcript, which is a gated route. The
same bytes then travel to the media queue, which read a questionnaire clip as NOT_WORKSHOP_MATERIAL
and sent it ungated. A clip on a REFUSED workshop would therefore have been refused at the microphone
and handed to ElevenLabs by the drain two hours later: one artisan's voice, one consent answer, two
opposite outcomes, and the second one silent. **A gate that only one of two paths honours is not a
gate**, so these tests pin both halves of the resolution that closed it.

THE HALF THAT MUST NOT MOVE IS THE OTHER ONE. ``stage_attached_workshop_ids``'s own docstring records
that of 528 AUDIO rows on this deployment, "279 of them are transcribed questionnaire material this
consent says nothing about" — interviews taken by researchers who are running no design workshop at
all. Sweeping those into a workshop's consent regime would make the archive's interviews
un-transcribable until somebody answered a question about a workshop they were never part of. So the
rule is narrow and is asserted as such: **a questionnaire clip is a workshop's material exactly when
its own interview says it is**, and where the interview says nothing the verdict is byte-for-byte
what it has always been.

No database: ``db`` is replaced per test, so what is pinned is the resolution logic rather than a
schema. Each fake read also RECORDS whether it was called, because "an artisan portrait does not pay
for an interview lookup" is a claim about the number of queries and cannot be tested by its result.
"""

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
    """Just enough of the Prisma client for the two reads this module makes."""

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

    async def _workshop(self, where):
        self.workshop_lookups.append(where["id"])
        return self._workshops.get(where["id"])


def _install(monkeypatch, *, interviews=None, workshops=None) -> _FakeDb:
    fake = _FakeDb(interviews or {}, workshops or {})
    monkeypatch.setattr(dictation_consent, "db", fake)
    return fake


def _clip(**overrides):
    """A stored questionnaire recording, in the shape the queue holds one."""
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


# ---------------------------------------------------------------------------------------------
# The half that must not move
# ---------------------------------------------------------------------------------------------


async def test_an_interview_that_names_no_workshop_is_exactly_as_ungated_as_before(monkeypatch):
    """The 279 archived interviews. NULL is not a refusal and must never become one."""
    _install(monkeypatch, interviews={"interview-1": _interview(None)})
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.NOT_WORKSHOP_MATERIAL
    assert verdict.may_send is True
    assert verdict.refusal is None


async def test_an_interview_this_server_cannot_find_is_an_orphan_and_not_a_refusal(monkeypatch):
    """A clip whose parent was deleted. Refusing would strand it: refused jobs are not retried."""
    _install(monkeypatch, interviews={})
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.NOT_WORKSHOP_MATERIAL
    assert verdict.may_send is True


async def test_a_row_that_is_not_questionnaire_material_never_pays_for_the_lookup(monkeypatch):
    """The claim is about the number of queries, so the call log is the only thing that can prove it."""
    fake = _install(monkeypatch, interviews={"interview-1": _interview("ws-1")})
    portrait = {"id": "media-2", "linkedRecordType": "artisan", "linkedRecordId": "artisan-1"}
    verdict = await transcription_verdict(portrait)
    assert verdict.decision is SendDecision.NOT_WORKSHOP_MATERIAL
    assert fake.interview_lookups == []


# ---------------------------------------------------------------------------------------------
# The half that closed the hole
# ---------------------------------------------------------------------------------------------


async def test_an_interview_that_names_a_refused_workshop_refuses_the_queue_too(monkeypatch):
    """THE DEFECT THIS FILE IS ABOUT. The synchronous route already refused; the drain did not."""
    _install(
        monkeypatch,
        interviews={"interview-1": _interview("ws-1")},
        workshops={"ws-1": _workshop(DictationConsent.REFUSED)},
    )
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.REFUSED
    assert verdict.may_send is False
    # A refused send must carry the sentence that will be shown — a blank refusal reaches a designer
    # as nothing at all, which `SendVerdict.__post_init__` refuses at construction.
    assert verdict.refusal


async def test_an_interview_whose_workshop_was_never_asked_also_refuses(monkeypatch):
    """Fail closed. "Nobody has asked the artisan yet" stops a send exactly as a refusal does."""
    _install(
        monkeypatch,
        interviews={"interview-1": _interview("ws-1")},
        workshops={"ws-1": _workshop(DictationConsent.NOT_RECORDED)},
    )
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.REFUSED


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


async def test_a_workshop_that_cannot_be_read_at_all_refuses(monkeypatch):
    """An unknown consent costs a named artisan's recorded voice leaving the device. It fails closed."""
    _install(monkeypatch, interviews={"interview-1": _interview("ws-1")}, workshops={})
    verdict = await transcription_verdict(_clip())
    assert verdict.decision is SendDecision.REFUSED


# ---------------------------------------------------------------------------------------------
# How the interview is found
# ---------------------------------------------------------------------------------------------


async def test_the_typed_foreign_key_is_read_before_the_free_text_tag(monkeypatch):
    """The FK is written by the server from the same request; the tag is a free string."""
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
    """Matching loosely can only ever ADD a consent check — `tagged_workshop_id`'s own reasoning."""
    fake = _install(monkeypatch, interviews={"interview-1": _interview("ws-1")})
    assert (
        await interview_workshop_id(
            _clip(questionnaireInterviewId=None, linkedRecordType=spelling)
        )
        == "ws-1"
    )
    assert fake.interview_lookups == ["interview-1"]


async def test_a_failed_read_raises_rather_than_reading_as_no_workshop(monkeypatch):
    """None here means "no workshop", which means SEND. A swallowed exception would be permission."""

    class _Broken:
        questionnaireinterview = SimpleNamespace(
            find_unique=lambda where: (_ for _ in ()).throw(RuntimeError("the database blinked"))
        )

    monkeypatch.setattr(dictation_consent, "db", _Broken())
    with pytest.raises(RuntimeError):
        await interview_workshop_id(_clip())


# ---------------------------------------------------------------------------------------------
# Precedence: the interview joins the candidates, it does not win over them
# ---------------------------------------------------------------------------------------------


async def test_one_refusal_among_several_named_workshops_is_the_answer(monkeypatch):
    """A clip tagged to a GRANTED workshop but interviewed for a REFUSED one is refused.

    The module's existing rule — every workshop that names this file has to permit the send — is what
    makes this safe to add as a fourth source rather than as a preferred one. There is no id a caller
    can supply that turns a REFUSED answer into a send.
    """
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
