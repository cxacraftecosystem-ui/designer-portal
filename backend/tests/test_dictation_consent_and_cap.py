"""Tier 3 consent and the per-designer daily cap, pinned: the gate, the boundary, and the two doors.

Plan ``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §6 answers 1 and 3. What is protected here is the
half of this feature that would be wrong in the same way on every workshop and every designer.

**NO DATABASE AND NO NETWORK. NOTHING IN THIS FILE SKIPS.** Every rule under test is decided by a pure
function — ``dictation_consent`` returns *plans* instead of performing writes, and ``dictation_cap``'s
whole ceiling is arithmetic over an :class:`Allowance` value — so the rules can be asserted on a laptop
with no Postgres and no generated Prisma client. This repository's history says the untestable half is
the half that is wrong.

Two groups do run an event loop and neither reaches a database: the permission tests drive the real
router over HTTP with ``db`` replaced by a tripwire, and the *wired* gate tests await the route
functions directly with the workshop load, the provider call and the counter replaced — because wiring
three correct pieces together in the wrong order is exactly how a gate ends up missing.

WHAT EACH GROUP WOULD COST IN THE FIELD IF IT STOPPED HOLDING:

1. **Consent fails closed.** Anything unreadable as GRANTED must gate. If it resolved the other way —
   which is how the neighbouring ``DwPackState`` rule resolves an unknown, deliberately — a null on a
   restored row would send a named artisan's recorded voice to a third party. An unknown pack costs a
   moment; an unknown consent costs somebody's voice.
2. **The default is NOT_RECORDED and never GRANTED.** A migration defaulting the other way would
   silently clear a third-party send for every workshop already in the database, which is precisely the
   failure the plan rejects an account-level consent for.
3. **Consent carries who and when, and the two clocks stay apart.** A consent recorded in a courtyard
   reaches the server on the next sync, a fortnight later; storing the sync moment would be a signature
   dated to the day it was filed.
4. **Consent can never be written into a stage row.** That is the registry temptation, refused by
   construction: the plan constructor and the executor both have to be edited to open that door.
5. **The day boundary is midnight IST.** A UTC day resets a designer's allowance at 05:30 IST,
   mid-morning to this fleet.
6. **The counter only counts what reached a provider.** A clip refused for size and a 503 for an
   unconfigured server must not consume an allowance; an empty or failed transcription must.
7. **Every refusal is a sentence naming a next move, never a code.** These strings are shown verbatim
   to a designer standing in a courtyard with an artisan waiting.
8. **The id-less door stays shut, and the probe stays answerable.** ``POST /design-workshops/dictate``
   transcribed clips without consulting any workshop's consent; it now refuses, spending nothing to do
   it. The GET at the same address is the browser's "does this deployment offer dictation at all"
   probe, which has to answer before any workshop id exists — and which, until that literal route was
   declared, was being answered 404 by ``GET /{workshop_id}`` looking up a workshop called "dictate".
   That is a routing property, so it is asserted over HTTP against the real table.
"""

import asyncio
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.services import dictation_consent
from app.services.dictation_cap import (
    DAY_BOUNDARY_PHRASE,
    Allowance,
    allowance_payload,
    cap_refusal,
    configured_cap,
    ist_day,
)
from app.services.dictation_consent import (
    DICTATION,
    MAX_DEVICE_CLOCK_SKEW,
    SENDS,
    ConsentRuleViolation,
    ConsentWritePlan,
    DictationConsent,
    Operation,
    Send,
    consent_keys,
    consent_of,
    decision_payload,
    decision_plans,
    gate_refusal,
    send_for,
)

SCHEMA_PRISMA = Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma"
MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "prisma"
    / "migrations"
    / "20260812120000_dw_dictation_consent_and_cap"
    / "migration.sql"
)

#: A fixed server clock, so nothing here depends on when the suite runs.
AT = datetime(2026, 8, 12, 9, 14, 2, tzinfo=UTC)

#: Every column ``workshop_summary`` reads off a workshop row, named one at a time rather than
#: generated. A column it gains and this list does not gets an ``AttributeError`` out of
#: ``SimpleNamespace`` — which is the point: a missing column must fail loudly here instead of arriving
#: on the wire as a null that looks exactly like a column nothing ever writes.
_SUMMARY_COLUMNS = (
    "title",
    "templateId",
    "status",
    "workshopCode",
    "scheme",
    "craftName",
    "clusterName",
    "state",
    "district",
    "venue",
    "startDate",
    "endDate",
    "designerName",
    "implementingAgency",
    "sponsor",
    "notes",
    "workshopId",
    "createdById",
    "createdAt",
    "updatedAt",
    "deletedAt",
    "dictationConsentAt",
    "dictationConsentById",
)


def _workshop_row(**overrides):
    """A ``DesignWorkshop`` row as Prisma hands it back, with every column ``workshop_summary`` reads.

    Built from a list of Nones rather than from a fixture factory so that a column added to
    ``workshop_summary`` and forgotten here fails loudly instead of reading as None.
    """
    columns = dict.fromkeys(_SUMMARY_COLUMNS)
    columns["id"] = "wsp_1"
    columns["dictationConsent"] = "NOT_RECORDED"
    columns.update(overrides)
    return SimpleNamespace(**columns)


def assert_sentence(text: str) -> None:
    """Every refusal in this lane is field copy: a real sentence, no error code, and it ends.

    Borrowed verbatim in spirit from ``DwDictationLadderTest.assertSentence`` on the handset, which
    holds the same bar for the same strings' Kotlin siblings. The ">30 characters" is what catches a
    later edit shortening one of these to "Not allowed."
    """
    assert text and text.strip(), "a refusal must say something"
    assert len(text) > 30, f"too terse to be an explanation: {text!r}"
    assert text.rstrip().endswith("."), f"not a sentence: {text!r}"
    assert "code " not in text.lower(), f"names an error code: {text!r}"


# --------------------------------------------------------------------------------------
# Consent: reading a stored answer, and failing closed
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "stored", ["NOT_RECORDED", "GRANTED", "REFUSED"]
)
def test_the_three_stored_answers_read_back_as_themselves(stored):
    assert consent_of(_workshop_row(dictationConsent=stored)) is DictationConsent(stored)


def test_an_enum_member_reads_back_as_well_as_a_plain_string():
    """Prisma hands enum columns back as either, depending on the client's generation, and a gate that
    worked on one and not the other would fail closed on a correctly consented workshop — a capability
    silently withdrawn, which nobody would report as a bug."""
    assert consent_of(_workshop_row(dictationConsent=DictationConsent.GRANTED)) is (
        DictationConsent.GRANTED
    )


@pytest.mark.parametrize("stored", [None, "", "granted", "YES", "TRUE", "NOT_A_TOKEN", 1])
def test_anything_unreadable_gates_rather_than_permitting(stored):
    """**FAIL CLOSED, AND IT INVERTS A NEIGHBOURING RULE ON PURPOSE.**

    ``packAllowsOnDevice`` on the handset resolves an UNKNOWN pack to *try it*, because "trying the
    free engine first costs a moment, while skipping it costs money on a phone that could have done the
    job". Consent resolves the unknown the other way, and this test is what stops somebody
    "restoring consistency": an unknown pack costs a moment, and an unknown consent costs a named
    artisan's recorded voice leaving the device for a third party.

    Note ``"granted"`` in the list. A case-insensitive read would be a kindness that turns a token no
    part of this system writes into a permission to send.
    """
    assert consent_of(_workshop_row(dictationConsent=stored)) is DictationConsent.NOT_RECORDED
    assert gate_refusal(consent_of(_workshop_row(dictationConsent=stored))) is not None


def test_a_row_with_no_consent_column_at_all_gates():
    """A record from a client, a test double, or a database restored from before the column existed.
    ``getattr`` finding nothing must not read as permission."""
    assert consent_of(SimpleNamespace(id="wsp_1")) is DictationConsent.NOT_RECORDED


# --------------------------------------------------------------------------------------
# Consent: the gate's two sentences
# --------------------------------------------------------------------------------------


def test_only_a_granted_workshop_passes_the_gate():
    assert gate_refusal(DictationConsent.GRANTED) is None
    assert gate_refusal(DictationConsent.NOT_RECORDED) is not None
    assert gate_refusal(DictationConsent.REFUSED) is not None


def test_the_two_refusals_are_different_sentences_with_different_next_moves():
    """NOT_RECORDED is answered by ASKING the artisan; REFUSED has already been asked and answered.

    One parameterised string would have to tell a designer holding a recorded refusal to go and ask the
    question again, which is the sort of instruction that teaches somebody to stop reading these
    messages — and the next one that matters goes with it.
    """
    unasked = gate_refusal(DictationConsent.NOT_RECORDED)
    refused = gate_refusal(DictationConsent.REFUSED)
    assert unasked != refused
    assert "record the artisan's answer" in unasked
    assert "that is the answer on record" in refused
    assert "if the artisan has since agreed" in refused.lower()


@pytest.mark.parametrize(
    "consent", [DictationConsent.NOT_RECORDED, DictationConsent.REFUSED]
)
def test_neither_refusal_tells_a_designer_to_try_again(consent):
    """Trying again is incapable of a different outcome: what changes a consent is a person deciding.

    Both sentences instead name the keyboard as the move that works NOW — the same discipline
    ``dwDictationNothingLeftSentence`` holds on the handset, where "type it in" is always the offer.
    """
    sentence = gate_refusal(consent)
    assert_sentence(sentence)
    assert "try again" not in sentence.lower()
    assert "type the words in" in sentence.lower()


# --------------------------------------------------------------------------------------
# Consent: the sentence names what is ACTUALLY being sent, and where
# --------------------------------------------------------------------------------------


def test_the_two_dictation_sentences_survive_this_refactor_byte_for_byte():
    """**PINNED CHARACTER FOR CHARACTER, AND THAT IS THE POINT OF PINNING THEM HERE.**

    ``gate_refusal`` now composes its refusal from a :class:`Send`, because it is called by six routes
    and was telling five of them a sentence about dictation. The composition had to be shaped around
    reproducing THESE two strings exactly: ``POST /{id}/dictate`` depends on them, the handset's
    ``DwDictationUpload`` prints them verbatim, and a designer who has read the dictation refusal a
    hundred times should not find it reworded today. An assertion on a substring would not have caught
    a comma moving, so these are the whole strings.
    """
    assert gate_refusal(DictationConsent.REFUSED) == (
        "This workshop's recordings may not be sent to the transcription service — that is the "
        "answer on record — so this dictation cannot be written down there. Type the words in "
        "instead. If the artisan has since agreed, change that answer on the workshop's own "
        "screen; nothing on this field can change it."
    )
    assert gate_refusal(DictationConsent.NOT_RECORDED) == (
        "Nobody has recorded yet whether recordings from this workshop may be sent to the "
        "transcription service, so this dictation cannot be written down there. Type the words in "
        "instead. Open the workshop's own screen and record the artisan's answer to that question — "
        "until somebody does, this stays unavailable."
    )
    # And the default really is that value rather than a copy of the same words.
    assert gate_refusal(DictationConsent.REFUSED, DICTATION) == gate_refusal(
        DictationConsent.REFUSED
    )


@pytest.mark.parametrize("consent", [DictationConsent.NOT_RECORDED, DictationConsent.REFUSED])
def test_no_verb_but_dictation_is_refused_with_a_sentence_about_dictation(consent):
    """**THE DEFECT, IN THE FIELD, VERBATIM.** ``_verb_gate`` calls this for all five AI verbs, so a
    designer pressing "describe this photograph" on a workshop nobody had asked about was told:
    *"Nobody has recorded yet whether recordings from this workshop may be sent to the transcription
    service, so this dictation cannot be written down there. Type the words in instead."*

    There is no dictation. No transcription service is involved — a caption goes to Gemini. The
    material is a photograph. And "type the words in instead" is not the alternative to describing an
    image; there is nothing to type. Both clients print the server's detail verbatim, so that WAS the
    field copy.
    """
    for verb, described in SENDS.items():
        sentence = gate_refusal(consent, described)
        assert "dictation" not in sentence.lower(), f"{verb} is still told about dictation"
        assert "type the words in" not in sentence.lower(), f"{verb} offers a keyboard"


def test_each_verb_names_the_service_its_route_actually_posts_to():
    """Written against the routes rather than against the plan: the three text verbs go to
    ``_post_openai_chat``, a caption goes to ``_post_gemini_caption``, and only subtitles reach the
    transcription chain. A sentence naming the wrong one is the same defect in a smaller font."""
    assert SENDS["PROOFREAD"].destination == "OpenAI's language model"
    assert SENDS["EXPAND"].destination == "OpenAI's language model"
    assert SENDS["TRANSLATE"].destination == "OpenAI's language model"
    assert SENDS["CAPTION"].destination == "Google's Gemini"
    assert SENDS["SUBTITLES"].destination == "the transcription service"
    assert DICTATION.destination == "the transcription service"
    for verb in ("PROOFREAD", "EXPAND", "TRANSLATE", "CAPTION"):
        assert "transcription" not in gate_refusal(DictationConsent.REFUSED, SENDS[verb])


def test_each_verb_names_the_material_it_actually_sends():
    """A caption's material is "a photograph or a video" and not "a photograph", because the gate runs
    BEFORE the media row is resolved — at the moment this sentence is composed the server genuinely
    does not know which, and ``_VERB_MEDIA_TYPES["CAPTION"]`` accepts both."""
    assert "photograph or a video" in SENDS["CAPTION"].consequence
    assert "recording" in SENDS["SUBTITLES"].consequence
    assert "passage" in SENDS["PROOFREAD"].consequence
    assert "note" in SENDS["EXPAND"].consequence
    assert "passage" in SENDS["TRANSLATE"].consequence


def test_a_media_verb_names_every_kind_of_file_its_route_would_accept():
    """**THE CONSENT REFUSAL AND THE WRONG-FILE REFUSAL MUST NOT HAVE TWO NAMES FOR ONE FILE.**

    ``_verb_gate`` runs before ``_verb_source_media``, so when this sentence is composed the server
    has not looked at the file and cannot know which kind it is. ``_VERB_MEDIA_TYPES`` already holds
    the words its own refusal uses for the pair — "a photograph or a video", "a recording or a
    video" — and the consent sentence has to use the same pair or it names material the verb does
    not send.

    CAPTION was written that way. SUBTITLES was not: it said "a recording", so a designer subtitling
    a VIDEO was told a recording could not be subtitled, by the same server that would have called
    that file a video one refusal later. Asserted against the route's own table rather than against
    a literal, so the day a verb learns a third media type the sentence cannot silently stay behind.
    """
    from app.api.routes.design_workshops import _VERB_MEDIA_TYPES

    for verb, (_tokens, in_words) in _VERB_MEDIA_TYPES.items():
        described = SENDS[verb]
        assert in_words in described.consequence, (
            f"{verb} accepts {in_words} and its consent refusal says "
            f"{described.consequence!r} — one file, two names, one refusal apart"
        )

    # And the alternative may not narrow it back again: "the recording is untouched" is a claim
    # about an audio file, made to somebody who may have picked a video.
    assert "recording" not in SENDS["SUBTITLES"].alternative


def test_only_the_dictation_answer_claims_the_artisan_was_asked_about_recordings():
    """The question actually put to the artisan, and the one this column stores, is about RECORDINGS.
    The server then reads that one answer as governing everything that leaves the device. So "this
    workshop's photographs may not be sent — that is the answer on record" would assert that somebody
    was asked about photographs, and nobody was."""
    assert DICTATION.material == "recordings"
    assert {described.material for described in SENDS.values()} == {"material"}


@pytest.mark.parametrize("verb", sorted(SENDS))
def test_both_consent_states_keep_their_distinct_next_moves_for_every_verb(verb):
    """The reason those two differ is the whole point of the module: NOT_RECORDED is answered by
    asking the artisan, and REFUSED has already been asked — telling somebody to go and ask again when
    the answer is on record teaches them to stop reading these messages. A parameterised sentence
    could easily have collapsed that distinction for the four verbs nobody was pinning."""
    unasked = gate_refusal(DictationConsent.NOT_RECORDED, SENDS[verb])
    refused = gate_refusal(DictationConsent.REFUSED, SENDS[verb])
    assert unasked != refused
    assert_sentence(unasked)
    assert_sentence(refused)
    assert "record the artisan's answer" in unasked
    assert "that is the answer on record" in refused
    assert "if the artisan has since agreed" in refused.lower()
    for sentence in (unasked, refused):
        assert "try again" not in sentence.lower()


def test_every_verb_this_server_runs_has_a_sentence_written_for_it():
    """A sixth verb added without copy would fall back to :data:`UNKNOWN_SEND`, which is vague — so
    this fails at the point where the copy can still be written, rather than in a courtyard."""
    from app.services.ai_verbs import Verb

    assert {verb.value for verb in Verb} == set(SENDS)
    for verb in Verb:
        assert send_for(verb.value) is SENDS[verb.value]
        assert send_for(verb) is SENDS[verb.value]


def test_an_unknown_verb_is_never_described_as_dictation():
    """The fallback is vague and false about nothing. Falling back to the dictation description would
    be exactly the defect this value was written to close, reappearing on the next verb."""
    unknown = send_for("EMBROIDER")
    assert unknown is not DICTATION
    sentence = gate_refusal(DictationConsent.REFUSED, unknown)
    assert_sentence(sentence)
    assert "dictation" not in sentence.lower()


def test_a_send_description_with_a_hole_in_it_is_refused_at_construction():
    """A refusal with a blank in it is a sentence a designer reads in a courtyard with a gap where the
    fact was."""
    with pytest.raises(ConsentRuleViolation):
        Send(material="", destination="x", consequence="y")
    with pytest.raises(ConsentRuleViolation):
        Send(material="material", destination="x", consequence="")
    with pytest.raises(ConsentRuleViolation):
        # Composed between two sentences: without the trailing space the next one runs into it.
        Send(material="material", destination="x", consequence="y", alternative="Do this instead.")


def test_no_verbs_alternative_offers_something_this_app_cannot_do():
    """Each alternative was checked against the app rather than imagined. EXPAND's is the one this
    repository actively prefers (``ai_verbs.expand``: "A designer who wants those words in the field
    types them"). CAPTION's names the caption box, which exists — the field registry carries eleven
    ``caption_for`` fields — and says "where the stage has one", because it is not on every media
    field and a designer sent to a box that is not on their screen stops reading these sentences.
    PROOFREAD, TRANSLATE and SUBTITLES have no manual equivalent at all, and say what was NOT done
    rather than inventing one."""
    from app.services.stage_schema import stages

    caption_fields = [
        field
        for stage in stages()
        for entity in stage.entities
        for field in entity.fields
        if getattr(field, "caption_for", "")
    ]
    assert caption_fields, "the caption box CAPTION's refusal points at does not exist"
    assert "caption box" in SENDS["CAPTION"].alternative
    assert "where the stage has one" in SENDS["CAPTION"].alternative

    assert "in your own words" in SENDS["EXPAND"].alternative
    for verb in ("PROOFREAD", "TRANSLATE", "SUBTITLES"):
        assert SENDS[verb].alternative.startswith("Nothing was sent")


# --------------------------------------------------------------------------------------
# Consent: the two writes, and the two clocks
# --------------------------------------------------------------------------------------


def test_one_decision_makes_exactly_two_writes_and_names_one_row():
    plans = decision_plans(
        workshop_id="wsp_1", decision=DictationConsent.GRANTED, actor_id="usr_2", at=AT
    )
    assert list(plans) == [plans.workshop, plans.decision]

    assert plans.workshop.table == "DesignWorkshop"
    assert plans.workshop.operation is Operation.UPDATE
    assert plans.workshop.where == {"id": "wsp_1"}
    assert plans.workshop.data == {
        "dictationConsent": "GRANTED",
        "dictationConsentAt": AT,
        "dictationConsentById": "usr_2",
    }

    assert plans.decision.table == "DwWorkshopConsentDecision"
    assert plans.decision.operation is Operation.CREATE
    assert plans.decision.where is None
    assert plans.decision.data == {
        "designWorkshopId": "wsp_1",
        "decision": "GRANTED",
        "note": None,
        "actorId": "usr_2",
        "recordedAt": None,
    }


def test_the_workshop_write_touches_only_the_three_consent_columns():
    """Enumerated rather than read, so a later change that "also bumps the status" or writes a stage key
    fails here. This write is reachable from a route gated only on ``_require_designer``; anything else
    it could set would be a field an artisan's consent form can edit by accident."""
    plans = decision_plans(
        workshop_id="wsp_1", decision=DictationConsent.REFUSED, actor_id="usr_2", at=AT
    )
    assert set(plans.workshop.data) == {
        "dictationConsent",
        "dictationConsentAt",
        "dictationConsentById",
    }


def test_the_courtyard_moment_is_what_lands_on_the_workshop():
    """**THE FORTNIGHT-OFFLINE CASE, which is the whole reason ``recordedAt`` exists.**

    An artisan answers on the 1st; the phone reaches signal on the 12th. What the workshop must say is
    the 1st — the moment somebody consented — and the server's own clock is left to the log row's
    ``createdAt``, which is not set here at all.
    """
    in_a_courtyard = datetime(2026, 7, 29, 11, 5, tzinfo=UTC)
    plans = decision_plans(
        workshop_id="wsp_1",
        decision=DictationConsent.GRANTED,
        actor_id="usr_2",
        at=AT,
        recorded_at=in_a_courtyard,
    )
    assert plans.workshop.data["dictationConsentAt"] == in_a_courtyard
    assert plans.decision.data["recordedAt"] == in_a_courtyard
    # The server's clock is NOT copied into the log row: `createdAt` is that table's own default, and a
    # copy here would be a second place for it to be wrong.
    assert "createdAt" not in plans.decision.data


def test_a_consent_recorded_against_the_server_leaves_the_device_moment_null():
    """Null and not a copy of ``at``. A copy would later read as "a device reported this", which is
    false, and would make the two columns indistinguishable for every consent recorded in an office."""
    plans = decision_plans(
        workshop_id="wsp_1", decision=DictationConsent.GRANTED, actor_id="usr_2", at=AT
    )
    assert plans.decision.data["recordedAt"] is None
    assert plans.workshop.data["dictationConsentAt"] == AT


def test_a_note_is_trimmed_to_nothing_rather_than_stored_blank():
    assert (
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.REFUSED,
            actor_id="usr_2",
            at=AT,
            note="   ",
        ).decision.data["note"]
        is None
    )
    assert (
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.REFUSED,
            actor_id="usr_2",
            at=AT,
            note="  she asked that the audio stay on the phone  ",
        ).decision.data["note"]
        == "she asked that the audio stay on the phone"
    )


def test_not_recorded_cannot_be_recorded_as_a_decision():
    """It is the absence of an answer, not an answer. "Somebody deliberately wrote down that nobody has
    been asked" is not a state anybody is in, and storing it would leave the gate unable to tell a
    withdrawn consent from a workshop nobody has opened. The refusal names REFUSED as the way to take a
    consent back."""
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.NOT_RECORDED,
            actor_id="usr_2",
            at=AT,
        )
    assert_sentence(str(exc.value))
    assert "REFUSED" in str(exc.value)


def test_an_unsigned_consent_is_refused():
    """This is the row that says a NAMED PERSON cleared an artisan's voice to leave the device. The
    log's actor is ``onDelete: Restrict`` for that reason, and a blank one would make the Restrict
    guard nothing."""
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="wsp_1", decision=DictationConsent.GRANTED, actor_id="  ", at=AT
        )
    assert_sentence(str(exc.value))


def test_a_consent_that_names_no_workshop_is_refused():
    """Per WORKSHOP is the whole decision (plan §6 answer 3). A write with no workshop id would be the
    account-level consent that was explicitly rejected, arrived at by accident."""
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="", decision=DictationConsent.GRANTED, actor_id="usr_2", at=AT
        )
    assert_sentence(str(exc.value))


def test_a_device_clock_in_the_future_is_refused_and_never_corrected():
    """A phone whose date was set by hand would otherwise put a consent in the log that appears to have
    been given after the report was submitted. It is REFUSED rather than silently replaced with now(),
    because a substituted timestamp is a fabricated fact about when somebody consented — the same rule
    ``AiLayerRegisterIn.producedAt`` holds one table over."""
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.GRANTED,
            actor_id="usr_2",
            at=AT,
            recorded_at=AT + timedelta(days=200),
        )
    assert_sentence(str(exc.value))
    assert "future" in str(exc.value)


def test_ordinary_handset_clock_drift_is_accepted():
    """A mobile-network clock a few minutes ahead is ordinary, and refusing it would reject honest
    consents recorded in a courtyard. The tolerance is a stated policy and not a measurement — nothing
    here measures the fleet's clock skew."""
    drifted = AT + MAX_DEVICE_CLOCK_SKEW - timedelta(seconds=1)
    plans = decision_plans(
        workshop_id="wsp_1",
        decision=DictationConsent.GRANTED,
        actor_id="usr_2",
        at=AT,
        recorded_at=drifted,
    )
    assert plans.workshop.data["dictationConsentAt"] == drifted


def test_a_consent_recorded_before_the_server_heard_of_it_is_always_accepted():
    """There is no lower bound and there must not be one. A workshop created in a courtyard reaches the
    server after the consent does, so "before the workshop's createdAt" is a legitimate shape and a
    guard against it would refuse exactly the offline case this column exists for."""
    long_ago = AT - timedelta(days=90)
    assert (
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.GRANTED,
            actor_id="usr_2",
            at=AT,
            recorded_at=long_ago,
        ).workshop.data["dictationConsentAt"]
        == long_ago
    )


def test_a_moment_with_no_offset_is_read_as_utc_rather_than_crashing():
    """**AN ISO-8601 MOMENT WITH NO OFFSET IS A LEGITIMATE THING FOR A CLIENT TO SEND**, and comparing
    one against the server's aware clock raises ``TypeError`` in Python — not a ``ConsentRuleViolation``,
    so the route's except clause would not catch it and a designer recording an artisan's answer would
    be handed "something went wrong on the server" for a consent they were entitled to file.

    Read as UTC, which is what ``dictation_cap.ist_day`` and the route's ``_parse_datetime`` already do
    with a naive moment, so the three of them cannot disagree about what a missing offset means.
    """
    naive_past = datetime(2026, 8, 12, 6, 0, 0)  # noqa: DTZ001 - the missing offset is the subject
    plans = decision_plans(
        workshop_id="wsp_1",
        decision=DictationConsent.GRANTED,
        actor_id="usr_2",
        at=AT,
        recorded_at=naive_past,
    )
    assert plans.workshop.data["dictationConsentAt"] == naive_past.replace(tzinfo=UTC)

    naive_future = datetime(2027, 1, 1, 0, 0, 0)  # noqa: DTZ001 - as above
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.GRANTED,
            actor_id="usr_2",
            at=AT,
            recorded_at=naive_future,
        )
    assert "future" in str(exc.value), "a naive future moment must reach the clock refusal, not a 500"


def test_a_naive_server_clock_does_not_crash_the_comparison_either():
    """The route passes ``datetime.now(UTC)``, but this function is public and a caller that passed a
    naive ``at`` would otherwise crash on the same comparison from the other side."""
    naive_now = datetime(2026, 8, 12, 9, 14, 2)  # noqa: DTZ001 - the missing offset is the subject
    plans = decision_plans(
        workshop_id="wsp_1", decision=DictationConsent.REFUSED, actor_id="usr_2", at=naive_now
    )
    assert plans.workshop.data["dictationConsentAt"] == naive_now.replace(tzinfo=UTC)


# --------------------------------------------------------------------------------------
# Consent: the registry door, held shut by construction
# --------------------------------------------------------------------------------------


def test_a_consent_can_never_be_written_into_a_stage_entry():
    """**THE REGISTRY TEMPTATION, REFUSED BY CONSTRUCTION RATHER THAN BY A DOCSTRING.**

    A consent question on stage 1 is the obvious-looking home and it cannot carry who and when:
    ``save_stage``'s UPDATE writes only {data, ordinal, deletedAt}, ``createdById`` is set on CREATE
    alone, and a stage row's ``updatedAt`` moves whenever anything in that stage changes. So there is no
    expressible write from that module into the stage table, and a later change that wants one has to
    delete this check — a visible act in a diff and a failing test, rather than a quiet new call site.
    """
    from app.services import dictation_consent as module

    with pytest.raises(ConsentRuleViolation) as exc:
        ConsentWritePlan(
            table=module.STAGE_TABLE,
            operation=Operation.UPDATE,
            where={"id": "ent_1"},
            data={"data": {"dictationConsent": "GRANTED"}},
        )
    assert module.STAGE_TABLE in str(exc.value)
    assert_sentence(str(exc.value))


def test_the_executor_has_no_writer_for_the_stage_table_either():
    """The second half of the same guard, and it is checked before the Prisma client is touched — so the
    refusal is a sentence on any machine, with or without a generated client."""
    from app.services import dictation_consent as module

    with pytest.raises(ConsentRuleViolation):
        module._writable_model(module.STAGE_TABLE)
    assert module.STAGE_TABLE not in module.WRITABLE_TABLES


def test_the_writable_set_is_the_two_tables_and_nothing_else():
    from app.services import dictation_consent as module

    assert sorted(module.WRITABLE_TABLES) == ["DesignWorkshop", "DwWorkshopConsentDecision"]


def test_an_update_must_name_one_row_and_a_create_must_name_none():
    with pytest.raises(ConsentRuleViolation):
        ConsentWritePlan(table="DesignWorkshop", operation=Operation.UPDATE, data={})
    with pytest.raises(ConsentRuleViolation):
        ConsentWritePlan(
            table="DwWorkshopConsentDecision",
            operation=Operation.CREATE,
            data={},
            where={"id": "x"},
        )


# --------------------------------------------------------------------------------------
# Consent: the wire
# --------------------------------------------------------------------------------------


def test_the_three_consent_keys_reach_the_clients():
    assert consent_keys(
        _workshop_row(
            dictationConsent="GRANTED", dictationConsentAt=AT, dictationConsentById="usr_2"
        )
    ) == {
        "dictationConsent": "GRANTED",
        "dictationConsentAt": AT.isoformat(),
        "dictationConsentById": "usr_2",
    }


def test_the_consent_key_is_never_null_on_the_wire():
    """A client must be able to render a state machine rather than infer one from an absence — the same
    reason ``ai_layers.layer_payload`` carries an explicit ``accepted`` boolean beside a nullable
    timestamp. An unreadable stored token arrives as NOT_RECORDED, which every client has a branch for,
    rather than as a raw token none of them does."""
    assert consent_keys(_workshop_row(dictationConsent=None))["dictationConsent"] == (
        "NOT_RECORDED"
    )
    assert consent_keys(_workshop_row(dictationConsent="WHAT"))["dictationConsent"] == (
        "NOT_RECORDED"
    )


def test_the_display_name_is_not_in_the_summary_keys():
    """Deliberately absent: this dict is serialised once per row by the paged LIST, and resolving a name
    there would be a query per workshop to print something the list does not show. The single-record
    read adds ``dictationConsentByName``."""
    assert "dictationConsentByName" not in consent_keys(_workshop_row())


def test_the_workshop_header_carries_the_consent_and_not_the_name():
    """The keys reach the clients through ``workshop_summary``, which is a hand-written dict — so a
    column added to schema.prisma and not to that dict is invisible on every surface and looks exactly
    like a column nothing writes."""
    from app.services.design_workshops import workshop_summary

    summary = workshop_summary(
        _workshop_row(dictationConsent="GRANTED", dictationConsentById="usr_2")
    )
    assert summary["dictationConsent"] == "GRANTED"
    assert summary["dictationConsentById"] == "usr_2"
    assert summary["dictationConsentAt"] is None
    assert "dictationConsentByName" not in summary


def test_a_recorded_decision_carries_both_moments():
    """A fortnight of no signal makes them differ by a fortnight, and a reader who can see only one
    cannot tell a consent given today from one given before the workshop was synced."""
    row = SimpleNamespace(
        id="dec_1",
        designWorkshopId="wsp_1",
        decision="GRANTED",
        note=None,
        actorId="usr_2",
        recordedAt=datetime(2026, 7, 29, 11, 5, tzinfo=UTC),
        createdAt=AT,
    )
    assert decision_payload(row) == {
        "id": "dec_1",
        "designWorkshopId": "wsp_1",
        "decision": "GRANTED",
        "note": None,
        "actorId": "usr_2",
        "recordedAt": "2026-07-29T11:05:00+00:00",
        "createdAt": AT.isoformat(),
    }


# --------------------------------------------------------------------------------------
# Consent: the schema and the migration
# --------------------------------------------------------------------------------------


def test_the_python_vocabulary_and_the_postgres_enum_agree():
    """Declared twice — once in Python, once in Postgres — and they must not drift. A value Python can
    produce and Postgres refuses is a 500 on the write path; a value Postgres holds and Python cannot
    name is a gate that cannot read a stored consent, which fails closed and silently withdraws a
    capability."""
    schema = SCHEMA_PRISMA.read_text(encoding="utf-8")
    match = re.search(
        r"^enum DwDictationConsent \{(.*?)^\}", schema, re.DOTALL | re.MULTILINE
    )
    assert match, "enum DwDictationConsent is not in schema.prisma"
    declared = {
        line.strip()
        for line in match.group(1).splitlines()
        if line.strip() and not line.strip().startswith("//")
    }
    assert declared == {c.value for c in DictationConsent}


def test_the_stored_default_is_not_recorded_and_never_granted():
    """**THE ONE LINE IN THIS FEATURE THAT MUST NEVER CHANGE.** A default of GRANTED would silently
    clear a third-party send for every workshop already in the database — precisely the failure the plan
    rejects an account-level consent for, arrived at through a migration instead of a setting. Pinned in
    both places it is written."""
    assert (
        "dictationConsent DwDictationConsent @default(NOT_RECORDED)"
        in SCHEMA_PRISMA.read_text(encoding="utf-8")
    )
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "\"DwDictationConsent\" NOT NULL DEFAULT 'NOT_RECORDED'" in sql
    assert "DEFAULT 'GRANTED'" not in sql


def test_the_decision_log_actor_outlives_the_account():
    """RESTRICT, exactly as ``DwAiLayerDecision.actor`` and ``ReviewLog.reviewer`` are. This row says a
    named person took responsibility for an artisan's voice leaving the device; an account deleted out
    from under it would leave the record saying somebody's recordings were cleared by nobody.

    And the CACHE pointer on the workshop is SetNull, which is the other half of the split: if such an
    account is ever removed by hand the workshop keeps its answer with an empty name, rather than the
    consent reverting to NOT_RECORDED and re-opening a question that had been settled.
    """
    schema = SCHEMA_PRISMA.read_text(encoding="utf-8")
    assert (
        'actor User @relation("DwWorkshopConsentDecisionActor", fields: [actorId], '
        "references: [id], onDelete: Restrict)" in schema
    )
    assert 'fields: [dictationConsentById], references: [id], onDelete: SetNull' in schema

    sql = MIGRATION.read_text(encoding="utf-8")
    assert (
        'ALTER TABLE "DwWorkshopConsentDecision" ADD CONSTRAINT '
        '"DwWorkshopConsentDecision_actorId_fkey" FOREIGN KEY ("actorId") REFERENCES "User"("id") '
        "ON DELETE RESTRICT" in sql
    )


def test_the_migration_is_additive_and_says_how_to_roll_back():
    """Every migration in this lane's neighbourhood states its rollback in the SQL, because the person
    who needs it will be reading this file at the time and not the plan."""
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "DROP TABLE \"DwDictationDailyUsage\";" in sql
    assert "DROP TABLE \"DwWorkshopConsentDecision\";" in sql
    assert 'DROP TYPE "DwDictationConsent";' in sql
    # Nothing is dropped or retyped on the way IN. A migration in this family that starts deleting
    # columns is one somebody has to read very differently.
    body = sql.split("-- CreateEnum", 1)[1]
    assert "DROP COLUMN" not in body
    assert "DROP TABLE" not in body


# --------------------------------------------------------------------------------------
# The cap: the day boundary, which is the whole meaning of "daily"
# --------------------------------------------------------------------------------------


def test_the_day_turns_over_at_midnight_india_time():
    """**THE BOUNDARY, ASSERTED ON BOTH SIDES OF ITSELF.** 18:29:59 UTC is 23:59:59 IST and still
    yesterday; 18:30:00 UTC is 00:00:00 IST and a new allowance. A UTC day would instead reset at 05:30
    IST — mid-morning to this fleet — which ``api/routes/public.py`` calls "visibly wrong to the people
    it is for" about the same choice for the census."""
    assert ist_day(datetime(2026, 8, 11, 18, 29, 59, tzinfo=UTC)) == "2026-08-11"
    assert ist_day(datetime(2026, 8, 11, 18, 30, 0, tzinfo=UTC)) == "2026-08-12"


def test_an_evening_in_india_is_still_today():
    """21:00 IST is 15:30 UTC on the same date, so nothing here is a coincidence of the two calendars
    agreeing: a designer refused at nine in the evening is refused against the day they are living in.
    """
    assert ist_day(datetime(2026, 8, 12, 15, 30, tzinfo=UTC)) == "2026-08-12"


def test_a_naive_clock_is_read_as_utc_rather_than_shifted_blindly():
    """A naive datetime shifted by +05:30 is only right if it happened to be UTC, and the mistake would
    be invisible for eighteen and a half hours out of every twenty-four — then wrong at exactly the
    boundary this function exists to define."""
    # DTZ001 is suppressed because the missing tzinfo IS THE SUBJECT of this test: the rule under test
    # is what `ist_day` does with a naive datetime, and a linter-pleasing tzinfo here would delete the
    # only assertion in the file that covers it.
    assert ist_day(datetime(2026, 8, 11, 18, 30, 0)) == "2026-08-12"  # noqa: DTZ001


def test_the_day_is_a_plain_calendar_date_string():
    """It is the second half of ``DwDictationDailyUsage``'s primary key and the ``dictationDay`` a phone
    compares its cached mirror against, so its shape is a contract and not a formatting choice."""
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", ist_day(AT))


# --------------------------------------------------------------------------------------
# The cap: the arithmetic
# --------------------------------------------------------------------------------------


def test_an_uncapped_server_never_refuses_and_counts_down_from_nothing():
    """NULL is the column's default, so this is what every deployment does on the day the migration
    runs. ``remaining`` is None rather than a large number: "no limit" and "a big limit" are different
    facts and only one of them can honestly be printed as a countdown."""
    allowance = Allowance(day="2026-08-12", limit=None, used=37)
    assert allowance.uncapped is True
    assert allowance.spent is False
    assert allowance.remaining is None
    assert cap_refusal(allowance) is None


def test_a_cap_refuses_only_once_it_is_reached():
    assert cap_refusal(Allowance(day="2026-08-12", limit=40, used=39)) is None
    assert cap_refusal(Allowance(day="2026-08-12", limit=40, used=40)) is not None


def test_a_cap_lowered_mid_day_still_refuses():
    """``>=`` and not ``==``, which is why this is a property rather than a comparison at the call site:
    a master admin who lowers the cap from 40 to 10 at noon must not hand every designer who has already
    spent 12 an unbounded afternoon because no equality will ever hold again."""
    over = Allowance(day="2026-08-12", limit=10, used=12)
    assert over.spent is True
    assert over.remaining == 0
    assert cap_refusal(over) is not None


def test_a_cap_of_zero_gets_its_own_sentence_and_never_blames_the_designer():
    """"You have used all 0 of today's dictations" reads as a bug, and a designer who has recorded
    nothing all morning being told they have used up their allowance would reasonably conclude the app
    is broken and stop trusting the next message. A cap of 0 is a deliberate setting — server dictation
    switched off — and the sentence says that instead."""
    refusal = cap_refusal(Allowance(day="2026-08-12", limit=0, used=0))
    assert_sentence(refusal)
    assert "all 0" not in refusal
    assert "used up" not in refusal
    assert "administers the server" in refusal


def test_the_refusal_names_the_limit_and_when_the_allowance_returns():
    """Plan §6.1 requires the cap be "named in words when it is hit", and naming a number requires
    having it. The boundary is named too, because a designer refused at 21:00 otherwise has no idea
    whether that means in three hours or in eleven — and it is the SERVER's India day, so the sentence
    says India time rather than "tomorrow", which a phone in another timezone would make false."""
    refusal = cap_refusal(Allowance(day="2026-08-12", limit=40, used=40))
    assert_sentence(refusal)
    assert "40" in refusal
    assert DAY_BOUNDARY_PHRASE in refusal
    assert "try again" not in refusal.lower()
    assert "type the words in" in refusal.lower()


def test_the_allowance_a_phone_caches_carries_the_day_it_belongs_to():
    """``dictationDay`` is the load-bearing key of this payload. Without it a phone cannot tell a cached
    "spent" that is still true from one that belongs to yesterday, and a mirror that guessed would
    silently withhold a capability at the wrong midnight."""
    assert allowance_payload(Allowance(day="2026-08-12", limit=40, used=28)) == {
        "dictationsLimit": 40,
        "dictationsUsed": 28,
        "dictationsRemaining": 12,
        "dictationDay": "2026-08-12",
    }


def test_an_uncapped_allowance_reports_nulls_and_not_zeroes():
    """0 remaining and "there is no ceiling" must not look alike on the wire: one of them means the next
    dictation will be refused."""
    payload = allowance_payload(Allowance(day="2026-08-12", limit=None, used=5))
    assert payload["dictationsLimit"] is None
    assert payload["dictationsRemaining"] is None
    assert payload["dictationsUsed"] == 5


# --------------------------------------------------------------------------------------
# The cap: reading the configured number
# --------------------------------------------------------------------------------------


def test_a_deployment_with_no_settings_row_is_uncapped():
    """Matching ``app_settings``' own convention: a deployment whose singleton has never been written
    behaves as the defaults."""
    assert configured_cap(None) is None


def test_null_is_uncapped_and_zero_is_a_real_setting():
    """**BOTH ARE REAL SETTINGS AND NEITHER IS A "NOT SET" SENTINEL**, which is why this is a function
    rather than a ``getattr`` at three call sites. 0 must survive as 0 — a truthiness test here would
    read it as "nothing configured" and quietly un-switch-off server dictation for the whole fleet."""
    assert configured_cap(SimpleNamespace(dwDictationDailyCap=None)) is None
    assert configured_cap(SimpleNamespace(dwDictationDailyCap=0)) == 0
    assert configured_cap(SimpleNamespace(dwDictationDailyCap=40)) == 40


def test_a_settings_row_that_predates_the_column_is_uncapped():
    """A stale generated client, or a row read before the migration ran. ``getattr`` finding nothing must
    mean today's behaviour and not "cap of zero", which would take dictation away fleet-wide."""
    assert configured_cap(SimpleNamespace()) is None


@pytest.mark.parametrize("stored", [-1, -40, "forty", object()])
def test_a_cap_nobody_could_have_meant_leaves_the_server_uncapped(stored):
    """The route refuses a negative, so one can only arrive by somebody editing the row by hand. Read as
    a ceiling it would clamp to 0 and silently withdraw craft-aware dictation from every designer in the
    fleet, with no message anywhere naming a mistyped number as the cause — the failure nobody would
    think to look for. Read as uncapped it restores what the deployment had before anybody typed
    anything, and the WARNING log makes the bad row findable."""
    assert configured_cap(SimpleNamespace(dwDictationDailyCap=stored)) is None


# --------------------------------------------------------------------------------------
# The cap: setting it, and being able to lift it again
#
# `configured_cap` above argues from a fact about the WRITE path — "the route refuses a negative, so one
# can only arrive by somebody editing the row by hand" — and nothing pinned that fact. These do.
# --------------------------------------------------------------------------------------


def test_zero_may_be_typed_and_a_negative_may_not():
    """0 is a real setting: server dictation off, with its own sentence. A negative is not a ceiling
    anybody meant, and it is refused HERE rather than reinterpreted downstream — which is the whole
    reason ``configured_cap`` is entitled to treat a stored negative as a hand-edited row."""
    from app.schemas.settings import MAX_DICTATION_DAILY_CAP, AppSettingUpdate

    assert AppSettingUpdate(dwDictationDailyCap=0).dwDictationDailyCap == 0
    assert (
        AppSettingUpdate(dwDictationDailyCap=MAX_DICTATION_DAILY_CAP).dwDictationDailyCap
        == MAX_DICTATION_DAILY_CAP
    )
    for refused in (-1, MAX_DICTATION_DAILY_CAP + 1):
        with pytest.raises(ValueError):
            AppSettingUpdate(dwDictationDailyCap=refused)


def test_an_explicit_null_is_distinguishable_from_never_mentioning_the_cap():
    """**THE ONLY WAY A CAP CAN EVER BE LIFTED AGAIN**, and it hangs on one line in
    ``PUT /settings`` that reads ``model_fields_set`` instead of trusting ``exclude_none``.

    Every other field on that body treats absent and null identically — "leave it alone" — which is
    right for a mode or a time that always has a value. Here null IS the setting that means uncapped, so
    under ``exclude_none`` alone a master admin who capped at 40 in the morning could never go back:
    the request lifting the cap would be byte-identical to one that never mentioned it. This pins both
    halves — that ``exclude_none`` really does drop it, and that ``model_fields_set`` really does tell
    the two requests apart — so a later "simplification" of that route fails here rather than silently
    making a ceiling permanent.
    """
    from app.schemas.settings import AppSettingUpdate

    lifting = AppSettingUpdate(dwDictationDailyCap=None)
    silent = AppSettingUpdate(batchWindowEnabled=True)

    assert "dwDictationDailyCap" in lifting.model_fields_set
    assert "dwDictationDailyCap" not in silent.model_fields_set
    # …and this is why the route cannot just read the dump: the instruction is invisible in it.
    assert "dwDictationDailyCap" not in lifting.model_dump(exclude_none=True)


def test_the_settings_route_reads_model_fields_set_for_the_cap_and_not_the_dump():
    """The assertion above is about pydantic; this one is about the route actually using it. Cheap, and
    it is the difference between "the mechanism exists" and "the mechanism is wired"."""
    source = (
        Path(__file__).resolve().parents[1] / "app" / "api" / "routes" / "settings.py"
    ).read_text(encoding="utf-8")
    assert '"dwDictationDailyCap" in payload.model_fields_set' in source


# --------------------------------------------------------------------------------------
# The gates, WIRED UP — the half that is usually wrong
#
# The route functions are awaited directly with the workshop load, the provider call and the counter
# replaced, so what is under test is what the pure tests above cannot reach: that the consent check,
# the ceiling check, the provider call and the increment are joined to each other in the right ORDER.
# --------------------------------------------------------------------------------------


class _Clip:
    """A dictated clip, as ``UploadFile`` presents one to the handler.

    ``reads`` is counted because "the refusal costs nothing" has to include the bytes: a handler that
    reads a six-megabyte upload into memory before refusing it has spent something, even if it never
    reaches a provider.
    """

    def __init__(self, size: int = 4096):
        self._bytes = b"\x00" * size
        self.filename = "dictation.webm"
        self.content_type = "audio/webm"
        self.reads = 0

    async def read(self) -> bytes:
        self.reads += 1
        return self._bytes


def _person(role: str):
    return SimpleNamespace(id="usr_1", email="x@example.test", name="Test", role=role)


@pytest.fixture
def dictation(monkeypatch):
    """The dictation routes with the workshop, the provider, the allowance and the counter replaced.

    Returns a recorder so each test can assert what was NOT done — which is most of what matters here:
    a refusal that still called the provider has spent the credit it was refusing.
    """
    from app.api.routes import design_workshops as routes

    seen = SimpleNamespace(provider_calls=[], spends=[], allowance=None, transcribed_for=[])

    async def _workshop(workshop_id, user, **kwargs):
        return _workshop_row(id=workshop_id, dictationConsent=state.consent)

    async def _transcribe(content, filename, mime, settings, *, user_id=None):
        # `user_id` IS RECORDED, NOT MERELY TOLERATED. It is what routes a dictation onto the
        # speaker's own provider key rather than the organisation's, so a stub that quietly swallowed
        # it would let the route stop passing it and every test here would still pass — while every
        # designer who had supplied a key silently went back to being billed to the deployment.
        seen.provider_calls.append((len(content), filename, mime))
        seen.transcribed_for.append(user_id)
        return dict(state.result)

    async def _load_allowance(user_id, *, now=None):
        return state.allowance

    async def _spend(user_id, day):
        seen.spends.append((user_id, day))
        return state.allowance.used + 1

    state = SimpleNamespace(
        consent="GRANTED",
        allowance=Allowance(day="2026-08-12", limit=None, used=0),
        result={"status": "COMPLETED", "text": "dabu resist printing", "provider": "deepgram"},
    )

    monkeypatch.setattr(routes, "load_workshop_or_404", _workshop)
    monkeypatch.setattr(routes, "transcribe_audio_bytes", _transcribe)
    monkeypatch.setattr(routes, "get_settings", SimpleNamespace)
    monkeypatch.setattr(routes.dictation_cap, "load_allowance", _load_allowance)
    monkeypatch.setattr(routes.dictation_cap, "spend", _spend)
    return SimpleNamespace(routes=routes, seen=seen, state=state)


@pytest.mark.parametrize("consent", ["NOT_RECORDED", "REFUSED", None, "granted"])
def test_the_workshop_scoped_route_refuses_an_unconsented_send_before_touching_a_provider(
    dictation, consent
):
    """**THE GATE, WIRED.** A 409 is not enough on its own: what makes this a gate rather than a
    message is that the provider was never called, so no credit was spent and no audio left this
    server. ``None`` and ``"granted"`` are in the list because the fail-closed read is part of the
    gate — a lower-case token no part of this system writes must not become a permission to send."""
    dictation.state.consent = consent
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 409
    assert_sentence(str(exc.value.detail))
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []


def test_a_consented_workshop_reaches_the_provider_and_gets_its_words_back(dictation):
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", file=_Clip(), languageHint="or", current_user=_person("DESIGNER")
        )
    )
    assert payload["text"] == "dabu resist printing"
    assert payload["languageHint"] == "or"
    assert len(dictation.seen.provider_calls) == 1
    assert dictation.seen.spends == [("usr_1", "2026-08-12")]
    # THE SPEAKER IS NAMED TO THE TRANSCRIBER, which is what lets their own provider key run this
    # dictation and take the charge. The route reads it off the token, so passing anything else — or
    # nothing — would bill the deployment for work a designer chose to pay for themselves.
    assert dictation.seen.transcribed_for == ["usr_1"]


def test_the_consent_gate_is_checked_before_the_cap(dictation):
    """A workshop with no consent is refused whatever the allowance says. Telling a designer their daily
    allowance is spent when the real blocker is a question nobody has asked the artisan would send them
    off to wait for midnight for nothing — the cap's refusal clears by itself, this one clears only when
    somebody records an answer."""
    dictation.state.consent = "NOT_RECORDED"
    dictation.state.allowance = Allowance(day="2026-08-12", limit=10, used=10)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 409, "the cap answered a question consent had already settled"


def test_a_spent_allowance_refuses_with_a_429_and_uploads_nothing_to_a_provider(dictation):
    """A 429 AND NOT A 403, because this clears itself at midnight India time and the sentence says so.
    A 403 would tell a client this account may never do this, which is what the consent gate means and
    this does not."""
    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=40)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 429
    assert "40" in str(exc.value.detail)
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []


def test_the_refusal_detail_is_a_sentence_and_not_a_dictionary(dictation):
    """The Android control prints ``detail`` verbatim for every answer that is not the route's own 503,
    so a dict here would put a Python repr in front of somebody in a village. The machine-readable copy
    of the same facts is on the 200 path and on ``GET /dictation-allowance``."""
    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=41)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert isinstance(exc.value.detail, str)


def test_a_clip_refused_for_size_does_not_consume_an_allowance(dictation):
    """It never reached a provider, so nothing was spent. Charging for it would make the ceiling arrive
    early for a reason the designer cannot see, and their next move — a shorter recording — would cost
    them twice."""
    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=0)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1",
                file=_Clip(size=dictation.routes.DICTATION_MAX_BYTES + 1),
                languageHint=None,
                current_user=_person("DESIGNER"),
            )
        )
    assert exc.value.status_code == 413
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []


def test_an_empty_upload_does_not_consume_an_allowance(dictation):
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", file=_Clip(size=0), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 422
    assert dictation.seen.spends == []


def test_a_server_with_no_provider_configured_does_not_consume_an_allowance(dictation):
    """**THE ONE REFUSAL A DESIGNER CAN DO NOTHING WHATEVER ABOUT.** A deployment with no API key would
    otherwise silently exhaust every designer's day, and the 503 they were shown would be replaced
    tomorrow morning by a cap message about dictations they never got."""
    dictation.state.result = {"status": "UNAVAILABLE", "message": "Transcription unavailable: …"}
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 503
    assert len(dictation.seen.provider_calls) == 1, "the chain was consulted"
    assert dictation.seen.spends == [], "…but nothing reached a provider, so nothing was spent"


@pytest.mark.parametrize("provider_status", ["COMPLETED", "EMPTY", "FAILED", "RATE_LIMITED"])
def test_every_upload_that_reached_a_provider_is_counted(dictation, provider_status):
    """**NOT ONLY ON SUCCESS.** The credit is spent by the call, so a run of empty clips still spends it
    — and counting only successes would leave the ceiling uncapped for exactly the failure mode that
    produces the most retries."""
    dictation.state.result = {"status": provider_status, "text": "", "provider": "deepgram"}
    asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert dictation.seen.spends == [("usr_1", "2026-08-12")]


def test_the_two_hundred_carries_the_allowance_so_a_phone_need_never_be_refused_to_learn_it(
    dictation,
):
    """**THIS IS WHY THE CAP IS NOT JUST A 429.** A phone that can learn the ceiling only by being
    refused has to spend a six-megabyte upload to learn it, and then another one tomorrow — the failure
    ``DwDictationUpload.kt`` already records for the 503. With these four keys on every successful
    dictation the handset knows from the last one whether the next is worth attempting, and can fall
    back to its own recogniser with zero bytes uploaded."""
    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=27)
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert payload["dictationsLimit"] == 40
    assert payload["dictationsUsed"] == 28
    assert payload["dictationsRemaining"] == 12
    assert payload["dictationDay"] == "2026-08-12"


def test_a_counter_that_could_not_be_written_reports_the_count_it_can_stand_behind(
    dictation, monkeypatch
):
    """The transcription has already happened and the words are already in the response, so a failed
    increment must not become a 500 for text the designer can see. What it must also not do is report an
    invented number: the response carries the count before the attempt rather than an optimistic one."""

    async def _failed_spend(user_id, day):
        return None

    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=27)
    monkeypatch.setattr(dictation.routes.dictation_cap, "spend", _failed_spend)
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert payload["dictationsUsed"] == 27
    assert payload["text"] == "dabu resist printing"


def test_the_id_less_route_transcribes_nothing_and_charges_nothing(dictation, monkeypatch):
    """**THE DOOR BESIDE THE GATE, SHUT — AND SHUT BEFORE ANYTHING IS SPENT.**

    ``POST /dictate`` carries no workshop id, so no artisan's ``dictationConsent`` could ever be
    consulted on it: for as long as it accepted a recording, a designer refused on the gated URL was
    transcribed on this one. Both clients have moved (Android's ``@POST("design-workshops/{id}/dictate")``
    and the browser's required ``workshopId``), so it refuses.

    A status code alone would not prove much. What makes this a retirement rather than a message is
    everything the three tripwires assert did NOT happen: no workshop was loaded, no allowance was read,
    no counter moved, no provider was called, and the upload was never even read into memory. A retired
    route that still charged a designer's daily allowance would be billing for a capability it no longer
    provides — and the designer would spend their afternoon watching the ceiling fall for dictations
    they never got.
    """

    async def _must_not_load_a_workshop(*args, **kwargs):
        raise AssertionError("the retired route has no workshop to load, and must not grow one")

    async def _must_not_read_an_allowance(*args, **kwargs):
        raise AssertionError("a route that transcribes nothing must not touch a designer's allowance")

    monkeypatch.setattr(dictation.routes, "load_workshop_or_404", _must_not_load_a_workshop)
    monkeypatch.setattr(
        dictation.routes.dictation_cap, "load_allowance", _must_not_read_an_allowance
    )

    clip = _Clip()
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate(
                file=clip, languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 410
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []
    assert clip.reads == 0, "a refusal that reads the upload has spent something to say no"


def test_the_retirement_names_the_url_that_replaced_it(dictation):
    """410 GONE, and a sentence rather than a code, because two people read this one string.

    A developer whose build still posts here needs the URL and the reason; the designer holding that
    build in a courtyard needs a next move that works this afternoon, and the Android control prints the
    server's ``detail`` verbatim to them. It must not say "try again": no retry of this request can
    succeed, and the thing that changes the outcome is a new build.

    404 would say "this server has never had dictation" — false, and it would send an operator hunting a
    deployment problem. 403 would say "not you" — false in the other direction, since every caller is
    refused identically.
    """
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate(
                file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    detail = str(exc.value.detail)
    assert_sentence(detail)
    assert "/design-workshops/{workshop_id}/dictate" in detail
    assert "try again" not in detail.lower()
    assert "type the words in" in detail.lower()


def test_the_probe_answers_without_a_workshop_and_names_where_a_clip_goes(dictation):
    """**THE QUESTION THE PER-WORKSHOP URL CANNOT BE ASKED**: does this deployment offer dictation?

    The browser asks it before any workshop is known — the microphone is drawn on fields a designer
    dictates into before the stage has ever been saved — so the answer cannot depend on a workshop id,
    and this route takes none. It also spends nothing: no allowance, no provider, no database.

    The payload names the gated URL because a route that says "yes, and it is over there" is the only
    honest shape for this answer now that the URL being probed is not the URL being posted to.
    """
    payload = asyncio.run(dictation.routes.dictation_probe(_person("DESIGNER")))
    assert payload["dictatePath"] == "/design-workshops/{workshop_id}/dictate"
    assert payload["allowancePath"] == "/design-workshops/dictation-allowance"
    assert payload["maxBytes"] == dictation.routes.DICTATION_MAX_BYTES
    assert payload["consentRequired"] is True
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []


# --------------------------------------------------------------------------------------
# The gates: the existing ones, on the new routes
#
# Borrowed wholesale from tests/test_ai_layers.py, including its reasoning: the real router is mounted
# and driven over HTTP with ``db`` replaced by a tripwire that raises the moment anything reads a
# delegate off it. That makes the two outcomes unambiguous with no schema and no database — a refusal is
# a 403 with the tripwire never touched (so the gate fired before any work), and an authorisation is the
# tripwire raising (so every guard passed and the handler body began). "Not a 403" would also pass for a
# route that 404s for an unrelated reason; these two cannot.
# --------------------------------------------------------------------------------------


class _DatabaseTouched(Exception):
    """The route's guards all passed and its body started working."""


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


_CALLER: dict[str, object] = {"user": None}


@pytest.fixture
def api(monkeypatch):
    """The design-workshop router, mounted with every module's ``db`` rebound to the tripwire.

    The modules do ``from app.core.db import db``, so each holds its OWN reference and patching the
    source alone would miss all of them — including ``app.services.dictation_cap`` and
    ``app.services.dictation_consent``. Rebinding by identity finds every one already imported.
    """
    import sys

    import httpx
    from fastapi import FastAPI

    import app.core.db as core_db
    from app.api.routes import design_workshops as routes
    from app.core import deps

    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if (
            getattr(module, "__name__", "").startswith("app.")
            and getattr(module, "db", None) is real_db
        ):
            monkeypatch.setattr(module, "db", tripwire)

    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]

    def call(role: str, method: str, path: str, body=None, files=None, data=None):
        _CALLER["user"] = _person(role)

        async def run():
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport, base_url="http://dictation.test"
            ) as c:
                response = await c.request(
                    method,
                    f"/api{path}",
                    json=body if files is None and data is None else None,
                    files=files,
                    data=data,
                )
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return SimpleNamespace(
                reached=False, status_code=response.status_code, detail=str(detail)
            )

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return SimpleNamespace(reached=True, status_code=None, detail="")

    yield call
    _CALLER["user"] = None


#: A real multipart body, because FastAPI validates the form parts BEFORE the handler runs — a JSON
#: body would 422 on the missing ``file`` and the designer gate would never be reached at all, so the
#: test would pass for the wrong reason.
CLIP = {"file": ("dictation.webm", b"\x00" * 32, "audio/webm")}

CONSENT_AND_DICTATION = [
    ("POST", "/design-workshops/wsp_1/dictation-consent", {"decision": "GRANTED"}, None),
    ("POST", "/design-workshops/wsp_1/dictate", None, CLIP),
    ("GET", "/design-workshops/dictation-allowance", None, None),
]
# ``POST /design-workshops/dictate`` is deliberately NOT in that list any more. It is retired, so it
# neither refuses by rank nor reaches a handler that works — it answers every caller identically, which
# is what the two tests at the end of this file assert instead.


@pytest.mark.parametrize(("method", "path", "body", "files"), CONSENT_AND_DICTATION)
@pytest.mark.parametrize("role", ["RESEARCHER", "PROFESSOR"])
def test_only_the_designer_set_may_record_a_consent_or_spend_a_dictation(
    api, role, method, path, body, files
):
    """``_require_designer``, on every one of them, and PROFESSOR is the account that proves it is a SET.

    ``DESIGN_WORKSHOP_ROLES`` is {DESIGNER, ADMIN, MASTER_ADMIN}. A PROFESSOR sits at rank 40, ABOVE
    DESIGNER's 35, so every "this tier and above" spelling of the rule lets them in and the set does
    not — a researcher alone cannot prove the distinction, because the ladder refuses them too.

    The tripwire proves the refusal came from the gate rather than from a stray 403 later on: the
    handler never read a delegate off the database.
    """
    outcome = api(role, method, path, body, files=files)
    assert outcome.reached is False
    assert outcome.status_code == 403


@pytest.mark.parametrize(("method", "path", "body", "files"), CONSENT_AND_DICTATION)
def test_a_designer_reaches_the_handler_and_then_the_database(api, method, path, body, files):
    """Past ``_require_designer``, the consent write and the workshop-scoped dictation both meet
    ``load_workshop_or_404`` — the workshop's own access check, where being the creator, an admin or a
    granted viewer is decided. No new gate is invented for either. The one id-less route left in the
    list has no workshop to load and meets the allowance read instead, which is its first act."""
    assert api("DESIGNER", method, path, body, files=files).reached is True


def test_the_consent_route_refuses_a_decision_nobody_can_record(api):
    """NOT_RECORDED is refused by the request body itself, so the tripwire is never touched: the
    workshop is not even loaded for a decision that could not be stored. It is the absence of an answer,
    and the message names REFUSED as the way to take a consent back."""
    outcome = api(
        "DESIGNER",
        "POST",
        "/design-workshops/wsp_1/dictation-consent",
        {"decision": "NOT_RECORDED"},
    )
    assert outcome.reached is False
    assert outcome.status_code == 422
    assert "REFUSED" in outcome.detail


@pytest.mark.parametrize("decision", ["MAYBE", "granted", "", "yes"])
def test_the_consent_route_refuses_a_token_postgres_would_have_rejected(api, decision):
    """The token reaches a Postgres enum column, so anything outside the three values was not merely
    stored wrong — Prisma refuses it and the route answered a bare 500, which reads to a client as "the
    server is broken" rather than "that is not an answer". The same failure
    ``DesignWorkshopUpdate._known_status_and_template`` was written for, one column over.

    Note ``"granted"``: the body normalises case before deciding, so a lower-case token from a client is
    accepted as GRANTED rather than refused — which is the opposite of :func:`consent_of`'s rule on the
    READ side, and deliberately so. A client typing a decision has somebody watching the response; a
    stored token nobody can read has to gate.
    """
    outcome = api(
        "DESIGNER", "POST", "/design-workshops/wsp_1/dictation-consent", {"decision": decision}
    )
    if decision.upper() == "GRANTED":
        assert outcome.reached is True
        return
    assert outcome.reached is False
    assert outcome.status_code == 422


def test_the_consent_route_refuses_an_unparseable_moment(api):
    """``ExportRecordIn.generatedAt`` falls back to now() on a malformed value, which is right there —
    an export's time is approximately now by definition. It is wrong here: a client that SAYS when the
    artisan answered and is silently recorded as having said nothing produces a consent dated to the
    sync."""
    outcome = api(
        "DESIGNER",
        "POST",
        "/design-workshops/wsp_1/dictation-consent",
        {"decision": "GRANTED", "recordedAt": "last Tuesday"},
    )
    assert outcome.reached is False
    assert outcome.status_code == 422


def test_the_consent_route_is_not_patch(api):
    """PATCH's writable set is a hand-written tuple copied in a loop that records neither the actor nor
    the moment, and its schema documents itself as the route for "an admin correcting a list entry
    without opening the stage". A value whose entire point is who set it and when cannot ride it — so
    the field is not accepted there, and a client that tries is told rather than silently ignored."""
    outcome = api(
        "DESIGNER",
        "PATCH",
        "/design-workshops/wsp_1",
        {"dictationConsent": "GRANTED"},
    )
    assert outcome.reached is False
    assert outcome.status_code == 422


# --------------------------------------------------------------------------------------
# The retired door, and the probe that has to survive it
#
# These run over HTTP rather than against the functions, because what they are about is ROUTING: which
# route object answers a request, which is a property of the table and not of any handler. The tripwire
# is what makes the answer unambiguous — a route that reads the database has, by definition, not been
# answered by either of the two id-less routes, neither of which touches it.
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("role", ["DESIGNER", "RESEARCHER", "PROFESSOR"])
def test_the_retired_url_refuses_every_caller_identically(api, role):
    """410 for all three ranks, and NOT 403 for the two that ``_require_designer`` turns away.

    The rank gate is deliberately not consulted here. "Running a design workshop requires Designer
    access or above" is a true sentence about a route that works and a misleading one about a route that
    is gone: it names getting access as the next move, and getting access to this URL would achieve
    nothing. What every caller needs to be told is the same thing — the address moved.

    The tripwire proves the refusal is the route's own and not a 410 from somewhere further in: nothing
    read a delegate off the database, so no workshop was loaded and no allowance was counted.
    """
    outcome = api(role, "POST", "/design-workshops/dictate", None, files=CLIP)
    assert outcome.reached is False
    assert outcome.status_code == 410
    assert "{workshop_id}/dictate" in outcome.detail


def test_the_capability_probe_is_answered_by_its_own_route_and_never_by_the_wildcard(api):
    """**THE REGRESSION THIS WHOLE PAIR OF ROUTES EXISTS FOR, AND IT COST A FEATURE ONCE.**

    ``serverOffersRoute`` in ``frontend/lib/designWorkshops.ts`` GETs ``/design-workshops/dictate`` and
    reads only the status: 404 means "this deployment has no dictation" and anything else means it has.
    A POST-only ``/dictate`` cannot answer a GET, and ``{workshop_id}`` matches any single segment — so
    the probe used to full-match ``GET /{workshop_id}``, look up a workshop whose id is "dictate", and
    get a 404 out of ``load_workshop_or_404``. The browser concluded there was no dictation on the
    server and rendered NO MICROPHONE AT ALL wherever the browser has no ``SpeechRecognition`` of its
    own — Firefox ships none, which is precisely the case the server fallback was built for.

    ``reached is False`` is the whole assertion: the literal route answers, so nothing looks up a
    workshop called "dictate". A 200 alone would not catch a regression here, because a deployment that
    happened to hold such a row would also answer 200 — from the wrong route.
    """
    outcome = api("DESIGNER", "GET", "/design-workshops/dictate")
    assert outcome.reached is False, "the wildcard workshop route swallowed the probe again"
    assert outcome.status_code == 200
    assert "/design-workshops/{workshop_id}/dictate" in outcome.detail


def test_the_probe_never_answers_404_for_a_rank(api):
    """A researcher's probe must not read as "this deployment has no dictation".

    The probe cannot tell a 403 from a 200 — both mean "offered" — so gating this route by rank would
    change no client's behaviour. What would be a real defect is a 404 for anybody, because that is the
    one status the browser acts on by hiding the control.
    """
    for role in ("DESIGNER", "RESEARCHER", "PROFESSOR"):
        assert api(role, "GET", "/design-workshops/dictate").status_code != 404


# --------------------------------------------------------------------------------------
# …and the same two questions asked of the WHOLE API
#
# **THE FIXTURE ABOVE MOUNTS THIS ROUTER ALONE, AND THAT IS PRECISELY THE COLLISION IT CANNOT SEE.**
# Route order decides which handler answers, and order is a property of the assembled application — two
# routers can share the ``/design-workshops`` prefix, and the one mounted FIRST wins. ``app/api/router.py``
# carries a note recording that exact failure in production: ``design_workshops``' ``GET /{workshop_id}``
# swallowed ``/design-workshops/eligible-viewers``, answered 404 "Record not found", and left the admin's
# designer picker empty on a server that had the route. A future router mounted above this one would
# swallow the probe again and every test above would still pass.
#
# The only other assertion against a fully mounted application lives in ``tests/test_workshop_audio.py``,
# which SKIPS ITSELF without a local Postgres — so on a laptop with no database, and in any CI job
# without one, nothing executed it at all. These two need no database: the tripwire proves the answer
# came from a route that reads none.
# --------------------------------------------------------------------------------------


_WHOLE_API: list = []


def _whole_api():
    """Every router in ``app/api/router.py``, in its own mounting order, assembled once.

    Once and not per test: twenty-eight routers with their response models cost a couple of seconds to
    build, which is the same reason ``tests/test_permission_matrix.py`` holds its application at module
    level. Nothing request-scoped lives on it — the caller comes from ``_CALLER`` and the database from
    the fixture below.
    """
    if not _WHOLE_API:
        from fastapi import FastAPI

        from app.api.router import api_router
        from app.core import deps

        application = FastAPI()
        application.include_router(api_router)
        application.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]
        _WHOLE_API.append(application)
    return _WHOLE_API[0]


@pytest.fixture
def mounted(monkeypatch):
    """The whole API, driven over HTTP, with every module's ``db`` rebound to the tripwire.

    The same rebinding as the ``api`` fixture and for its reason: modules do ``from app.core.db import
    db`` and each holds its own reference, so patching the source alone would miss all of them.
    """
    import sys

    import httpx

    import app.core.db as core_db

    # BUILT BEFORE THE TRIPWIRE IS INSTALLED, AND THE ORDER IS NOT COSMETIC. Assembling the whole API
    # imports every route module for the first time, and at least one of them reads a delegate off ``db``
    # while it is being imported — with the tripwire already in place that raises during fixture SETUP,
    # which pytest reports as an ERROR in a test that never ran. Building first also means the rebinding
    # loop below sees the modules this import has just added.
    application = _whole_api()

    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if (
            getattr(module, "__name__", "").startswith("app.")
            and getattr(module, "db", None) is real_db
        ):
            monkeypatch.setattr(module, "db", tripwire)

    def call(role: str, method: str, path: str, files=None):
        _CALLER["user"] = _person(role)

        async def run():
            transport = httpx.ASGITransport(app=application)
            async with httpx.AsyncClient(
                transport=transport, base_url="http://dictation.test"
            ) as c:
                response = await c.request(method, f"/api{path}", files=files)
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return SimpleNamespace(
                reached=False, status_code=response.status_code, detail=str(detail)
            )

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return SimpleNamespace(reached=True, status_code=None, detail="")

    yield call
    _CALLER["user"] = None


def test_the_probe_is_answered_by_its_own_route_on_the_whole_api(mounted):
    """The microphone's existence, asserted against the application a deployment actually serves.

    ``reached is False`` is again the whole assertion: the literal route answers, so nothing looked up a
    workshop called "dictate". If any router mounted before this one grows a ``GET /{something}`` at the
    ``/design-workshops`` prefix, this is the test that fails — and the alternative is Firefox users
    finding no microphone on a build where every unit test passed.
    """
    outcome = mounted("DESIGNER", "GET", "/design-workshops/dictate")
    assert outcome.reached is False, "something ahead of the probe swallowed it"
    assert outcome.status_code == 200
    assert "/design-workshops/{workshop_id}/dictate" in outcome.detail


def test_the_retired_url_is_still_retired_on_the_whole_api(mounted):
    """The other half: nothing mounted elsewhere has put a working handler back at the shut door."""
    outcome = mounted("DESIGNER", "POST", "/design-workshops/dictate", files=CLIP)
    assert outcome.reached is False
    assert outcome.status_code == 410
    assert "{workshop_id}/dictate" in outcome.detail


# --------------------------------------------------------------------------------------
# 9. The gate for STORED recordings: whose workshop is this file, and may it be sent
#
# The half of this feature that was missing entirely. Consent gated the thirty-second dictation and
# not the eleven-minute interview, so an artisan who said no had their recording sent anyway — by
# `media_queue.enqueue_media_processing_jobs`, which is where every transcription job in this system
# is created and which read no consent column.
#
# ALL PURE. The classification is a function of two columns on a row, and the verdict is a function of
# a consent already read, so both are asserted here with no database — which is the point, because the
# defect they close was invisible to 2,344 passing tests.
# --------------------------------------------------------------------------------------


def _media(**columns):
    """A media row as the gate reads it. A dict, because ``tagged_workshop_id`` accepts either."""
    return {"id": "media-1", **columns}


def test_a_design_workshop_upload_names_its_workshop_in_the_columns_it_already_sends():
    """The lookup that was called impossible. Both clients have always sent these two values.

    The recorded reason the queue was not gated is that ``MediaFile`` has no foreign key to
    ``DesignWorkshop``. True, and it does not follow that the workshop is unknowable: every
    design-workshop upload arrives tagged, and the tag carries the id.
    """
    row = _media(linkedRecordType="designWorkshop", linkedRecordId="ws-7")
    assert dictation_consent.tagged_workshop_id(row) == "ws-7"


def test_the_tag_is_read_whatever_case_a_client_spelled_it_in():
    """``linkedRecordType`` is a free string that nothing on the server normalises — the handset
    constant's own docstring says two spellings would file one workshop's photographs in two buckets.
    Matching loosely can only ever ADD a consent check, so it is the safe direction to be lax in."""
    assert dictation_consent.tagged_workshop_id(
        _media(linkedRecordType="  DesignWorkshop  ", linkedRecordId=" ws-7 ")
    ) == "ws-7"


def test_a_recording_that_belongs_to_no_design_workshop_is_left_alone():
    """RULE 4, AND IT IS THE ONE A FAIL-CLOSED CHANGE IS MOST LIKELY TO BREAK. A questionnaire
    interview clip has been transcribed automatically since long before this consent existed, and no
    answer about a design workshop says anything about it. Refusing it would stop the repository's
    oldest working pipeline in the name of a permission nobody was asked for."""
    for row in (
        _media(linkedRecordType="questionnaireInterview", linkedRecordId="int-1"),
        _media(linkedRecordType=None, linkedRecordId=None),
        _media(),
    ):
        assert dictation_consent.tagged_workshop_id(row) is None
        verdict = dictation_consent.verdict_for(None, workshop_id=None)
        assert verdict.decision is dictation_consent.SendDecision.NOT_WORKSHOP_MATERIAL
        assert verdict.may_send is True
        assert verdict.refusal is None


def test_an_unanswered_workshop_refuses_the_send_and_says_which_screen_answers_it():
    verdict = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.NOT_RECORDED, workshop_id="ws-7"
    )
    assert verdict.may_send is False
    assert verdict.workshop_id == "ws-7"
    assert "Nobody has recorded yet" in verdict.refusal
    # The alternative is true of THIS send and of no other: a refused transcription still leaves the
    # audio, because the consent question tells the artisan the recording is kept to be listened to.
    assert "still kept with the workshop" in verdict.refusal


def test_a_refusal_on_record_gets_the_other_sentence_and_never_says_ask_again():
    verdict = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.REFUSED, workshop_id="ws-7"
    )
    assert verdict.may_send is False
    assert "that is the answer on record" in verdict.refusal
    assert "Nobody has recorded yet" not in verdict.refusal


def test_a_workshop_the_server_could_not_read_at_all_refuses():
    """FAIL CLOSED, and this is the case the whole module docstring's asymmetry argument is about: a
    workshop that does not exist, was deleted, or whose read raised. ``None`` is not "no opinion" —
    it is "I cannot tell", and the only safe answer to that about somebody else's voice is no."""
    verdict = dictation_consent.verdict_for(None, workshop_id="ws-gone")
    assert verdict.may_send is False
    assert "Nobody has recorded yet" in verdict.refusal


def test_a_granted_workshop_may_send_and_carries_no_sentence():
    verdict = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.GRANTED, workshop_id="ws-7"
    )
    assert verdict.decision is dictation_consent.SendDecision.GRANTED
    assert verdict.may_send is True
    assert verdict.refusal is None


def test_a_verdict_cannot_refuse_without_a_sentence_to_show():
    """A refusal with nothing to say reaches a designer as a blank dialog. Refused by construction,
    in the shape ``Send.__post_init__`` already uses for the same failure."""
    with pytest.raises(dictation_consent.ConsentRuleViolation):
        dictation_consent.SendVerdict(dictation_consent.SendDecision.REFUSED)
    with pytest.raises(dictation_consent.ConsentRuleViolation):
        dictation_consent.SendVerdict(
            dictation_consent.SendDecision.GRANTED, refusal="something to say"
        )


def test_the_recording_refusal_never_mentions_dictation_or_typing_the_words_in():
    """The defect ``Send`` was parameterised to end, in its newest form. An eleven-minute interview
    is not a dictation and there is nothing to type instead of one — telling a designer to "type the
    words in" about an artisan's recorded explanation is the same wrong sentence that was being shown
    for captions."""
    refusal = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.REFUSED, workshop_id="ws-7"
    ).refusal
    assert "dictation" not in refusal.lower()
    assert "Type the words in" not in refusal


def test_a_transcript_on_its_way_to_openai_names_openai_and_not_the_transcription_service():
    """``POST /media/{id}/refine-transcript`` posts the artisan's words to a DIFFERENT company than
    the one that produced them. A refusal naming the wrong recipient is the same defect in a smaller
    font — see ``dictation_consent.Send``."""
    refusal = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.NOT_RECORDED,
        workshop_id="ws-7",
        send=dictation_consent.REFINEMENT,
    ).refusal
    assert "OpenAI's language model" in refusal
    assert "the transcription service" not in refusal


def test_the_queue_reads_consent_again_before_it_fetches_a_single_byte():
    """THE SECOND DEFENCE, AND THE ORDER INSIDE IT IS THE ASSERTION.

    ``media_queue._process_job`` re-reads consent at the moment of sending, because the enqueue check
    alone cannot see a withdrawal: a job queued under a grant sits in the table until the off-peak
    window opens, and ``dictation_consent``'s own docstring names that artisan — agrees on the 3rd,
    changes their mind on the 9th.

    What is pinned here is that the refusal happens BEFORE ``get_object_bytes``. A gate placed after it
    would still not send, but it would pull the whole recording out of object storage first, on every
    refused clip, every pass — and on a fleet whose recordings run past an hour that is the difference
    between a cheap refusal and a bandwidth bill. The tripwire is the byte fetch, so this test fails
    both if the send happens and if the ordering is reversed.

    No database and no network: the verdict, the two writers and the fetch are all replaced.
    """
    import asyncio

    from app.services import media_queue

    fetched: list[str] = []
    finalized: list = []

    def _never(*args, **kwargs):
        fetched.append("bytes were read out of object storage for a refused recording")
        raise AssertionError(fetched[-1])

    async def _refuse(media, **kwargs):
        return dictation_consent.SendVerdict(
            dictation_consent.SendDecision.REFUSED,
            workshop_id="ws-7",
            refusal="the artisan withdrew",
        )

    async def _record(job, verdict):
        finalized.append(verdict)

    async def _boom(*args, **kwargs):
        raise AssertionError("a provider was contacted for a recording consent had refused")

    job = SimpleNamespace(
        id="job-1",
        jobType="TRANSCRIPTION",
        mediaFileId="m1",
        mediaFile=SimpleNamespace(
            id="m1",
            objectKey="media/u/1/clip.webm",
            originalFilename="clip.webm",
            mimeType="audio/webm",
            transcriptText=None,
            linkedRecordType="designWorkshop",
            linkedRecordId="ws-7",
        ),
    )

    original = (
        media_queue.dictation_consent.transcription_verdict,
        media_queue._finalize_refused_job,
        media_queue.get_object_bytes,
        media_queue.transcribe_audio_bytes,
    )
    media_queue.dictation_consent.transcription_verdict = _refuse
    media_queue._finalize_refused_job = _record
    media_queue.get_object_bytes = _never
    media_queue.transcribe_audio_bytes = _boom
    try:
        asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    finally:
        (
            media_queue.dictation_consent.transcription_verdict,
            media_queue._finalize_refused_job,
            media_queue.get_object_bytes,
            media_queue.transcribe_audio_bytes,
        ) = original

    assert fetched == [], fetched
    assert len(finalized) == 1, "the job was neither sent nor closed — it would be retried for ever"
    assert finalized[0].refusal == "the artisan withdrew"


def test_a_measurement_job_is_not_touched_by_the_consent_gate():
    """MEASUREMENT sends a photograph of an object on a grid sheet to a vision model. No consent
    question in this repository asks about that, and gating it on an answer about recordings would be
    the overreach this module's own ``Send.material`` note argues against — in the other direction.

    Asserted by letting the byte fetch run: if the gate had claimed this job, the fetch would never be
    reached and the recorded call list would be empty.
    """
    import asyncio

    from app.services import media_queue

    reached: list[str] = []

    def _fetch(key):
        reached.append(key)
        return b"jpegbytes"

    async def _refuse_everything(media, **kwargs):
        raise AssertionError("the consent gate was consulted for a measurement job")

    async def _analysis(*args, **kwargs):
        return {"status": "COMPLETED", "analysis": None}

    async def _applied(job, result):
        reached.append("applied")

    job = SimpleNamespace(
        id="job-2",
        jobType="MEASUREMENT",
        mediaFileId="m2",
        mediaFile=SimpleNamespace(
            id="m2", objectKey="media/u/2/grid.jpg", originalFilename="grid.jpg",
            mimeType="image/jpeg", transcriptText=None,
            linkedRecordType="designWorkshop", linkedRecordId="ws-7",
        ),
    )

    original = (
        media_queue.dictation_consent.transcription_verdict,
        media_queue.get_object_bytes,
        media_queue.analyze_measurement_image_bytes,
        media_queue._apply_measurement_result,
    )
    media_queue.dictation_consent.transcription_verdict = _refuse_everything
    media_queue.get_object_bytes = _fetch
    media_queue.analyze_measurement_image_bytes = _analysis
    media_queue._apply_measurement_result = _applied
    try:
        asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    finally:
        (
            media_queue.dictation_consent.transcription_verdict,
            media_queue.get_object_bytes,
            media_queue.analyze_measurement_image_bytes,
            media_queue._apply_measurement_result,
        ) = original

    assert reached == ["media/u/2/grid.jpg", "applied"], reached
