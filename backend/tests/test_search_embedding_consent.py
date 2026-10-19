"""Search embeddings send text to OpenAI or Gemini, so people's words are embedded only with consent.

A ``transcripts`` document asks ``transcription_verdict`` for its recording, an ``interviews``
document asks ``interview_verdict`` and a ``designWorkshops`` document (every stage's text) asks
``workshop_verdict``: the one consent store, the same question every other outside send asks.

No database and no provider: ``search_indexer.db`` is a small in-memory stand-in for the two tables
the embedder reads (``SearchDocument`` and the ``Consent`` ledger's newest row), ``embed`` records
what it was given, and each consent question answers from a dict. The last test runs the real
questions over ``consent_store_fakes.LedgerDb``.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from consent_store_fakes import LedgerDb, workshop

from app.services import dictation_consent, search_embeddings, search_indexer

ALLOW = dictation_consent.SendVerdict(dictation_consent.SendDecision.GRANTED, workshop_id="w")
REFUSE = dictation_consent.SendVerdict(
    dictation_consent.SendDecision.REFUSED, workshop_id="w", refusal="Consent was refused."
)


class _Db:
    def __init__(self) -> None:
        self.docs: list[dict[str, Any]] = []
        self.consent_rows: list[datetime] = []
        self.media: dict[str, Any] = {}
        self.mediafile = SimpleNamespace(find_unique=self._media)

    async def _media(self, where: dict[str, Any]) -> Any:
        return self.media.get(where["id"])

    def add(self, entity_type: str, entity_id: str, text: str) -> dict[str, Any]:
        row = {"id": f"{entity_type}:{entity_id}", "entityType": entity_type, "entityId": entity_id,
               "contentHash": f"h-{entity_id}", "embeddedHash": None, "text": text,
               "updatedAt": len(self.docs)}
        self.docs.append(row)
        return row

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        if 'FROM "Consent"' in sql:
            latest = max(self.consent_rows) if self.consent_rows else None
            return [{"at": latest, "n": len(self.consent_rows)}]
        batch, _chars, *prefixes = args
        out = [d for d in self.docs
               if d["embeddedHash"] != d["contentHash"]
               and all(d["embeddedHash"] != p + d["contentHash"] for p in prefixes)]
        return [dict(d) for d in sorted(out, key=lambda d: d["updatedAt"])[:batch]]

    async def execute_raw(self, sql: str, *args: Any) -> int:
        if "LIKE" in sql:
            hits = [d for d in self.docs if str(d["embeddedHash"] or "").startswith(args[0].rstrip("%"))]
            for d in hits:
                d["embeddedHash"] = None
            return len(hits)
        if '"embedding"' in sql:
            _vector, content_hash, doc_id = args
        else:
            content_hash, doc_id = args
        for d in self.docs:
            if d["id"] == doc_id:
                d["embeddedHash"] = content_hash
        return 1


@pytest.fixture
def world(monkeypatch):
    db = _Db()
    sent: list[str] = []
    consent: dict[str, Any] = {}

    async def available(_settings=None):
        return True

    async def embed(texts, *, settings=None, timeout=30.0):
        sent.extend(texts)
        return [[0.0] * search_embeddings.DIMENSIONS for _ in texts]

    def asks(kind):
        async def verdict(entity_id):
            return consent.get(f"{kind}:{entity_id}", REFUSE)

        return verdict

    monkeypatch.setattr(search_indexer, "db", db)
    monkeypatch.setattr(search_embeddings, "available", available)
    monkeypatch.setattr(search_embeddings, "embed", embed)
    for kind in search_indexer.CONSENT_QUESTIONS:
        monkeypatch.setitem(search_indexer.CONSENT_QUESTIONS, kind, asks(kind))
    monkeypatch.setitem(search_indexer._consent_seen, "mark", None)
    search_indexer.reset_backoff()
    yield db, sent, consent
    search_indexer.reset_backoff()


def _run(coro: Any) -> Any:
    return asyncio.run(coro)


def test_the_three_kinds_of_peoples_words_are_the_gated_ones():
    assert {"transcripts", "interviews", "designWorkshops"} == search_indexer.CONSENT_GATED_TYPES


@pytest.mark.parametrize("kind", ["transcripts", "interviews", "designWorkshops"])
def test_words_without_consent_are_never_sent_and_do_not_block_the_rest(world, kind):
    db, sent, _consent = world
    held = db.add(kind, "x1", "the artisan's words")
    db.add("crafts", "c1", "Dabu printing")
    assert _run(search_indexer.embed_pending(batch=1)) == 1
    assert sent == ["Dabu printing"]
    assert held["embeddedHash"] == search_indexer.EMBED_CONSENT_PREFIX + held["contentHash"]
    assert _run(search_indexer.embed_pending()) == 0
    assert sent == ["Dabu printing"]


@pytest.mark.parametrize("kind", ["transcripts", "interviews", "designWorkshops"])
def test_a_withdrawn_consent_stops_the_next_embedding(world, kind):
    db, sent, consent = world
    doc = db.add(kind, "x1", "first words")
    consent[f"{kind}:x1"] = ALLOW
    assert _run(search_indexer.embed_pending()) == 1
    consent[f"{kind}:x1"] = REFUSE
    db.consent_rows.append(datetime.now(UTC))
    doc["contentHash"], doc["text"] = "h-x1-2", "edited words"
    assert _run(search_indexer.embed_pending()) == 0
    assert sent == ["first words"]


def test_consent_given_later_is_picked_up_by_itself(world):
    db, sent, consent = world
    db.add("transcripts", "m1", "the artisan's words")
    db.consent_rows.append(datetime.now(UTC) - timedelta(days=1))
    assert _run(search_indexer.embed_pending()) == 0
    assert _run(search_indexer.embed_pending()) == 0, "no new consent: the mark holds"
    consent["transcripts:m1"] = ALLOW
    db.consent_rows.append(datetime.now(UTC))
    assert _run(search_indexer.embed_pending()) == 1
    assert sent == ["the artisan's words"]


def test_an_unreadable_consent_sends_nothing_and_is_asked_again(world, monkeypatch):
    db, sent, _consent = world
    db.add("transcripts", "m1", "the artisan's words")

    async def broken(_entity_id):
        raise RuntimeError("database blinked")

    monkeypatch.setitem(search_indexer.CONSENT_QUESTIONS, "transcripts", broken)
    assert _run(search_indexer.embed_pending()) == 0
    assert sent == [] and db.docs[0]["embeddedHash"] is None


def test_the_real_questions_read_the_one_consent_store(world, monkeypatch):
    """The three questions, unreplaced, over the ledger fake: a recording refusal, an artisan linked
    to the workshop refusing, and the workshop's own answer each keep their document here."""
    db, sent, _consent = world
    ledger = LedgerDb()
    monkeypatch.setattr(dictation_consent, "db", ledger)
    for kind, question in _ORIGINAL.items():
        monkeypatch.setitem(search_indexer.CONSENT_QUESTIONS, kind, question)
    ledger.workshops["ws-1"] = workshop("ws-1", "GRANTED")
    ledger.rows["WORKSHOP"]["ws-1"] = "GRANTED"
    ledger.interviews["i1"] = SimpleNamespace(id="i1", designWorkshopId="ws-1")
    db.questionnaireinterview = ledger.questionnaireinterview
    db.media["m1"] = SimpleNamespace(id="m1", linkedRecordType="designWorkshop", linkedRecordId="ws-1",
                                     questionnaireInterviewId=None, artisanId=None)
    db.add("transcripts", "m1", "a stage clip")
    db.add("interviews", "i1", "answers")
    db.add("designWorkshops", "ws-1", "stage text")
    assert _run(search_indexer.embed_pending()) == 3
    assert sorted(sent) == ["a stage clip", "answers", "stage text"]

    sent.clear()
    ledger.workshop_artisans["ws-1"] = ["a1"]
    ledger.rows["ARTISAN"]["a1"] = "REFUSED"
    db.consent_rows.append(datetime.now(UTC))
    for doc in db.docs:
        doc["contentHash"] += "-2"
    assert _run(search_indexer.embed_pending()) == 0
    assert sent == [], "an artisan linked to the workshop refused: none of the three may leave"


#: The real questions, kept before any test replaces them.
_ORIGINAL = dict(search_indexer.CONSENT_QUESTIONS)
