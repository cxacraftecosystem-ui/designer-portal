"""H3 (audit 21 Oct 2026): an instant transcript asks the interview's own artisans, not only the
workshop's linked ones.

``POST /design-workshops/{id}/dictate`` used to ask ``workshop_verdict``, which covers only artisans
whose ``Artisan.designWorkshopId`` is that workshop. An interviewed artisan who refused but is not
linked to the workshop was still sent to the provider. The route now asks ``dictation_verdict`` with
the interview's id and the artisans picked on the form.

No database and no provider: ``dictation_consent.db`` is ``consent_store_fakes.LedgerDb``.
"""

from __future__ import annotations

import inspect

import pytest
from consent_store_fakes import LedgerDb, workshop

from app.services import dictation_consent

pytestmark = pytest.mark.anyio


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture
def ledger_db(monkeypatch) -> LedgerDb:
    fake = LedgerDb()
    monkeypatch.setattr(dictation_consent, "db", fake)
    fake.workshops["ws-1"] = workshop("ws-1", "GRANTED")
    fake.rows["WORKSHOP"]["ws-1"] = "GRANTED"
    return fake


async def test_an_interviewed_artisan_not_linked_to_the_workshop_refuses_the_dictation(ledger_db):
    ledger_db.participants["i1"] = ["outsider"]
    ledger_db.rows["ARTISAN"]["outsider"] = "REFUSED"
    assert (await dictation_consent.workshop_verdict("ws-1")).may_send, "the old gate missed them"
    verdict = await dictation_consent.dictation_verdict("ws-1", interview_id="i1")
    assert not verdict.may_send
    assert verdict.refusal and "refused" in verdict.refusal


async def test_an_artisan_picked_on_an_unsaved_form_refuses_the_dictation(ledger_db):
    ledger_db.rows["ARTISAN"]["a9"] = "REFUSED"
    assert (await dictation_consent.dictation_verdict("ws-1", artisan_ids=["a1"])).may_send
    assert not (await dictation_consent.dictation_verdict("ws-1", artisan_ids=["a1", "a9"])).may_send


async def test_the_workshops_linked_artisans_still_count(ledger_db):
    ledger_db.workshop_artisans["ws-1"] = ["linked"]
    ledger_db.rows["ARTISAN"]["linked"] = "REFUSED"
    assert not (await dictation_consent.dictation_verdict("ws-1", interview_id="i1")).may_send


async def test_participants_who_agreed_or_never_answered_are_sent(ledger_db):
    ledger_db.participants["i1"] = ["a1", "a2"]
    ledger_db.rows["ARTISAN"]["a1"] = "GRANTED"
    assert (await dictation_consent.dictation_verdict("ws-1", interview_id="i1")).may_send


def test_split_ids_drops_blanks_and_repeats():
    assert dictation_consent.split_ids(" a1, ,a2,a1 ") == ["a1", "a2"]
    assert dictation_consent.split_ids(None) == []


def test_the_dictate_route_asks_about_the_interview_and_the_picked_artisans():
    from app.api.routes import design_workshops

    source = inspect.getsource(design_workshops.dictate_for_workshop)
    assert "dictation_verdict(" in source
    assert "interview_id=interviewId" in source
    assert "split_ids(artisanIds)" in source
    assert "workshop_verdict(" not in source.split('"""')[-1]
