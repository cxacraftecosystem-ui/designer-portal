"""Tier 3 consent: may THIS workshop's recordings leave the device, who said so, and when.

Plan ``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §6 answer 3, in the user's own words: *"Consent
for Tier 3. ANSWERED: per workshop, recorded, and it gates rung 2. A workshop carries an explicit
answer to 'may recordings and dictation from this workshop leave the device for a third-party
provider', with who set it and when. Until it is answered yes, rung 2 is unavailable and says why."*

**WHY PER WORKSHOP AND NOT PER ACCOUNT, since that is the setting somebody will propose again.** A
consent given for one cluster would silently cover the next one, and the artisan whose voice it is
changes between them. One designer works six clusters in a season; an account-level switch would
mean the weaver in the second village consented by way of the dyer in the first. That is not consent,
it is a checkbox with somebody else's name on it.

================================================================================================
WHERE IT LIVES, AND WHY IT IS NOT A FIELD IN THE REGISTRY
================================================================================================

Three columns on ``DesignWorkshop`` plus an append-only ``DwWorkshopConsentDecision`` log. A consent
question added to stage 1 as a ``FieldSpec`` was the obvious-looking home and it is wrong five
independent ways, any one of them sufficient:

1. **It cannot carry who and when — decisive on its own**, because that is the entire content of this
   decision. ``save_stage``'s UPDATE branch writes exactly ``{data, ordinal, deletedAt}``;
   ``DwStageEntry.createdById`` is set on the CREATE branch alone; ``DwStageEntry.updatedAt`` is
   ``@updatedAt`` and moves whenever *any* field in that stage's row changes. A consent stored there
   would be attributed to whoever first saved stage 1 and dated to the last edit of any of stage 1's
   fields — an artisan's answer, credited to a colleague and dated to a typo correction.
2. **It moves ``registry_version()``**, which is the refetch signal for a file compiled into the APK:
   the 119 KB ``assets/design-workshop-schema.json`` a handset renders forms from before it has ever
   reached the network. One new ``FieldSpec`` marks that asset stale on every handset in the fleet.
3. **It forces an Android release.** Two tests pin that asset — one to the digest, one to the full
   ``registry_to_dict()`` content.
4. **The repository already wrote the rule down**, one migration ago, for the immediately preceding
   feature: ``custom_sections.py`` — *"``registry_to_dict()`` gains nothing — not a key, not a flag,
   not one string. Anything added there fails the suite until 119 KB is re-dumped, and re-dumping it
   is an Android release."*
5. **It would move every existing workshop's completeness**, because the scorer walks the registry and
   counts every non-deprecated singleton field into ``requiredTotal``/``optionalTotal``. A column
   touches no score at all.

That argument is made true by construction here rather than only stated: :class:`ConsentWritePlan`
refuses to name any table outside :data:`WRITABLE_TABLES`, and ``DwStageEntry`` is not in it. A later
change that wants consent inside a stage row has to delete that check, which is a visible act in a
diff and a failing test rather than a quiet new call site. The same door, the same guard, as
``ai_layers.LayerWritePlan``.

**THE CACHE AND THE LOG, copied in shape and in argument from ``DwAiLayer``/``DwAiLayerDecision``.**
The three columns are the current answer, read by the gate on a request a designer is waiting on. The
log is the history, and it is not optional: consent can be **withdrawn**, and a withdrawal would
erase the answer the sends were made under. An artisan agrees on the 3rd, nine dictations go to a
provider over the following week, the artisan changes their mind on the 9th — with columns alone the
database now says this workshop never permitted anything, and nothing is left that explains the nine
transcripts sitting in it. "Granted on the 3rd, withdrawn on the 9th" is only answerable from a log.

================================================================================================
TWO CLOCKS, AND WHY BOTH ARE KEPT
================================================================================================

A consent recorded in a courtyard reaches the server on the next sync, which on this fleet can be a
fortnight later. So:

* :func:`decision_plans` takes ``recorded_at`` — **when the artisan actually answered**, as the device
  that was there reported it — and that is what lands in ``DesignWorkshop.dictationConsentAt`` and in
  the log row's ``recordedAt``.
* the log row's own ``createdAt`` is the server's clock and always says when the server heard it.

Collapsing them would fabricate one of the two answers: a consent dated to the sync is a signature
dated to the day it was filed. This is ``DwAiLayer.producedAt`` versus ``createdAt``, one table over,
for the same reason.

A device clock that is WRONG is the price of trusting it, so a ``recorded_at`` in the future is
refused rather than stored — see :data:`MAX_DEVICE_CLOCK_SKEW`.

================================================================================================
FAIL CLOSED, AND IT INVERTS A NEIGHBOURING RULE ON PURPOSE
================================================================================================

Anything this module cannot read as GRANTED gates the send: an unknown enum token, a null, a row
restored from before the column existed. That is the opposite of ``DwPackState``'s honest-unknown
resolution on the handset, where an UNKNOWN pack resolves to *try it* — *"trying the free engine
first costs a moment, while skipping it costs money on a phone that could have done the job"*. The
asymmetry is deliberate and is written here so that a later reader "restoring consistency" has to
argue with it: **an unknown pack costs a moment; an unknown consent costs a named artisan's recorded
voice leaving the device for a third party.** There is no symmetric version of that.

**AND CONSENT GATES RUNG 2 ONLY. IT NEVER GATES RUNG 1.** A phone with an installed on-device pack
keeps dictating with no consent question at all, offline and for free, *because nothing leaves the
device*. Nothing in this module is reachable from that path, and nothing here may grow a caller that
makes it so.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any

from app.core.db import db

logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------------------
# The vocabulary
# --------------------------------------------------------------------------------------


class DictationConsent(str, Enum):
    """Whether one workshop's recordings may be sent to a third-party provider.

    Mirrors the ``DwDictationConsent`` Postgres enum, and it is three states rather than a boolean
    because the third one is a different fact with a different next move. "Nobody has asked the
    artisan yet" and "the artisan said no" both stop a send; the first is answered by asking, and the
    second only by the artisan changing their mind. A boolean would have had to default to false for
    every workshop already in the database, making thirty thousand never-asked workshops
    indistinguishable from thirty thousand refusals nobody ever made.
    """

    #: Nobody has been asked. Every workshop that predates the column, and the default for new ones.
    NOT_RECORDED = "NOT_RECORDED"
    GRANTED = "GRANTED"
    REFUSED = "REFUSED"


#: How far ahead of the server's clock a device-reported ``recordedAt`` may be before it is refused.
#:
#: A handset's clock is set by the mobile network and drifts; a few minutes out is ordinary and
#: refusing it would reject honest consents recorded in a courtyard. A consent dated to next March is
#: not drift — it is a phone whose clock was set by hand, and storing it would put a consent in the
#: log that appears to have been given before the workshop existed or after the report was submitted.
#:
#: Fifteen minutes is a POLICY and not a measurement: nothing here measures the fleet's clock skew, and
#: the number is chosen to be generous enough that no honest device is refused. The refusal names the
#: next move (record it against the server) rather than silently substituting now(), because a
#: substituted timestamp is a fabricated fact about when somebody consented.
MAX_DEVICE_CLOCK_SKEW = timedelta(minutes=15)

#: The tables this module may write to, and the whole list.
#:
#: The guarantee the module docstring's five reasons argue for is made true HERE: ``DwStageEntry`` is
#: absent, and :class:`ConsentWritePlan` refuses to name a table that is not in this set. There is no
#: expressible write from this module into a stage row, which is what stops the registry temptation
#: from arriving as a quiet new call site.
WRITABLE_TABLES: frozenset[str] = frozenset({"DesignWorkshop", "DwWorkshopConsentDecision"})

#: Named so the refusal can name it, and so a reader grepping for the stage table finds this note.
STAGE_TABLE = "DwStageEntry"


class ConsentRuleViolation(ValueError):
    """A consent write that cannot be made, refused with a sentence naming the next move.

    A ``ValueError`` rather than an ``HTTPException``, so this module stays importable — and testable
    — with no framework underneath it, exactly as ``ai_layers.LayerRuleViolation`` and
    ``custom_sections.CustomSectionEditError`` are. The route turns it into a status code; the
    sentence it carries is written for the designer who will read it in a courtyard.
    """


def consent_of(row: Any) -> DictationConsent:
    """One workshop's current answer, read from a stored row and FAILING CLOSED.

    Anything this cannot recognise — a null on a row restored from before the column existed, a token
    a newer build wrote, a string with the wrong case — answers ``NOT_RECORDED``, which gates. It does
    not raise, because a workshop nobody can read the consent of must still be openable, printable and
    editable; the only thing that must not happen is a send.

    Contrast ``ai_layers.source_of``, which raises on a shape it cannot read. That one is deciding
    whether to PRINT a provenance record, where a wrong answer misinforms a reader and a refusal is
    recoverable. This one is deciding whether to send an artisan's voice to a third party, where the
    only safe answer to "I cannot tell" is no.
    """
    raw = getattr(row, "dictationConsent", None)
    token = str(getattr(raw, "value", raw) or "")
    try:
        return DictationConsent(token)
    except ValueError:
        if token:
            # Logged rather than swallowed: a token outside the enum means either a newer build wrote
            # it or somebody edited the row by hand, and both are worth finding. It still gates.
            logger.warning(
                "dictation_consent: workshop %s carries the unreadable consent %r; treating it as "
                "NOT_RECORDED",
                getattr(row, "id", "?"),
                token,
            )
        return DictationConsent.NOT_RECORDED


# --------------------------------------------------------------------------------------
# The gate
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Send:
    """What is about to leave the device, where it is going, and what can be done instead.

    ================================================================================================
    WHY THIS EXISTS: ONE GATE WITH ONE HARDCODED NOUN IS NOT ONE GATE
    ================================================================================================

    :func:`gate_refusal` is called by six routes — ``POST /{id}/dictate`` and all five AI verbs — and
    for five of them it answered: *"…so this dictation cannot be written down there. Type the words in
    instead."* There is no dictation on any of those five. No transcription service is involved in
    four of them: a caption goes to Gemini and the three text verbs go to OpenAI. The material of a
    caption is a photograph. And "type the words in instead" is not the alternative to describing an
    image — there is nothing to type.

    **WHAT THAT DEFECT HAD AND HAD NOT DONE YET, MEASURED RATHER THAN DRAMATISED.** It is field copy
    by construction: a refusal is a FastAPI ``detail``, and both clients print a ``detail`` verbatim
    for the route each of them calls (``Dictation.tsx``'s 409 branch — *"where the server spoke, only
    the server speaks"* — and the handset's ``DwDictationUpload``). But **no client calls any of the
    five verb routes**: ``frontend/lib/aiLayers.ts`` binds list, register, accept, unaccept and
    delete and no verb, and the Android app binds none of them. There is no "describe this
    photograph" button in either client, so nobody has yet read the dictation sentence in a
    courtyard while captioning. The wrong sentence was real, reachable by any signed-in caller, and
    fixed before the button that would have shown it exists — which is the true account and the one
    worth keeping, because *"a designer already read this"* is a claim about a thing that did not
    happen. The dictation refusal on the same function IS live and IS read in the field today; see
    :data:`DICTATION`.

    **THE MODULE DOCSTRING'S ARGUMENT IS RIGHT AND IS NOT WEAKENED BY THIS.** One consent, one gate,
    one function — a second gate is a door beside the door, which is what ``POST /dictate`` was retired
    to a 410 for. What was wrong was never the single gate; it was a single SENTENCE, which is a
    different thing. So the decision stays in one function and the noun becomes a parameter.

    ================================================================================================
    THE FIELDS, AND THE RULE EACH ONE IS UNDER
    ================================================================================================

    ``material`` — the plural noun the consent clause uses of what this workshop holds. **It is
    ``"material"`` for everything except dictation, and that is deliberate rather than lazy.** The
    question actually put to the artisan, and the one this column stores, is about RECORDINGS (see
    ``schemas/design_workshops.DictationConsentIn`` and ``record_dictation_consent``); the server then
    applies that one answer to everything leaving the device, which is argued for at the verb gate.
    So "this workshop's photographs may not be sent — that is the answer on record" would assert that
    somebody was asked about photographs, and nobody was. "This workshop's material" is what the
    answer is being read as governing, which is the true statement.

    ``destination`` — where it actually goes, checked against the route rather than assumed. The three
    that exist: the transcription chain (ElevenLabs/Deepgram/Whisper — "the transcription service",
    which is this repository's own term for it and the one the cap's copy uses), OpenAI's chat model
    for the three text verbs, and Gemini for captions. A sentence that named the wrong one would be
    the same defect in a smaller font.

    ``consequence`` — what cannot happen, phrased to follow "so". Names the real material and the real
    verb.

    ``alternative`` — **a next move that EXISTS, or nothing at all.** Ending with a trailing space when
    present, because it sits between two sentences. Where a verb has no manual equivalent this is a
    true statement about what was not done rather than an invented instruction; the next move is then
    carried by the clause every refusal ends with, which is to record the artisan's answer or to
    change it.
    """

    material: str
    destination: str
    consequence: str
    alternative: str = ""

    def __post_init__(self) -> None:
        for name in ("material", "destination", "consequence"):
            if not str(getattr(self, name) or "").strip():
                raise ConsentRuleViolation(
                    f"A send description must name its {name}. A refusal with a blank in it is a "
                    f"sentence a designer reads in a courtyard with a hole where the fact was."
                )
        if self.alternative and not self.alternative.endswith(" "):
            raise ConsentRuleViolation(
                "An alternative is composed between two sentences and must end with the space that "
                "separates it from the next one."
            )


#: The default, and the ONLY one whose wording is pinned byte for byte.
#:
#: ``POST /{workshop_id}/dictate`` depends on these exact sentences and
#: ``tests/test_dictation_consent_and_cap.py`` asserts them. A designer who has read the dictation
#: refusal a hundred times must not find it reworded today, so the composition below is arranged so
#: that these four values reproduce the two original strings CHARACTER FOR CHARACTER — that is what
#: the parameterisation had to be shaped around, and it is asserted rather than eyeballed.
DICTATION = Send(
    material="recordings",
    destination="the transcription service",
    consequence="this dictation cannot be written down there",
    alternative="Type the words in instead. ",
)

#: One send description per AI verb, keyed by ``ai_verbs.Verb``'s own value.
#:
#: **KEYED BY THE VERB AND NOT BY THE ROUTE**, so the sentence a designer reads and the meter that
#: counts the run cannot come to disagree about which verb this was. Every value here was written
#: against what the route ACTUALLY sends, read out of ``api/routes/design_workshops.py``:
#:
#: * PROOFREAD, EXPAND, TRANSLATE -> ``ai.proofread_text`` / ``expand_text`` / ``translate_text``, all
#:   of which are ``_run_chat_verb`` -> ``_post_openai_chat``. Text, to OpenAI. **No transcription
#:   service is involved in any of them**, which is what the old sentence claimed for all three.
#: * CAPTION -> ``ai.caption_image_bytes`` -> ``_post_gemini_caption``. A photograph or a video, to
#:   Gemini. The pair is named because the gate runs BEFORE the media row is resolved, so at the
#:   moment this sentence is composed the server genuinely does not know which of the two it is —
#:   ``_VERB_MEDIA_TYPES["CAPTION"]`` accepts both, and its own refusal says "a photograph or a video".
#: * SUBTITLES -> ``ai.transcribe_timed_bytes``, which is the transcription chain narrowed to the two
#:   engines that return timings. This one really is the transcription service, and it is the only
#:   verb of the five for which the old sentence named the right destination.
SENDS: Mapping[str, Send] = {
    "PROOFREAD": Send(
        material="material",
        destination="OpenAI's language model",
        consequence="this passage cannot be proofread there",
        # No manual equivalent that this app can offer: a stored layer is a row and cannot be edited,
        # and a passage the designer typed is already theirs to correct without any of this.
        alternative="Nothing was sent, and the passage is exactly as you left it. ",
    ),
    "EXPAND": Send(
        material="material",
        destination="OpenAI's language model",
        consequence="this note cannot be written out there",
        # THE ONE VERB WITH A REAL ALTERNATIVE BESIDES DICTATION, and it is the alternative this
        # repository actively prefers: `ai_verbs.expand` — "A designer who wants those words in the
        # field types them, at which point they are that designer's sentences under that designer's
        # name — which is a true statement, unlike anything a paste button could produce."
        alternative="Write the note out in your own words instead — typed prose is yours rather than "
        "a machine's, which is what the report needs anyway. ",
    ),
    "TRANSLATE": Send(
        material="material",
        destination="OpenAI's language model",
        consequence="this passage cannot be translated there",
        alternative="Nothing was sent, and the passage stays in the language it was written in. ",
    ),
    "CAPTION": Send(
        material="material",
        destination="Google's Gemini",
        consequence="a photograph or a video from it cannot be described there",
        # ESTABLISHED RATHER THAN ASSUMED, because the obvious thing to write here is "you cannot
        # caption a photograph by hand in this app" and that is FALSE: the field registry carries
        # 23 `caption_for` fields (`stage_schema.FieldSpec.caption_for`), which BOTH clients render
        # as a caption box directly under the media control it captions — `FieldInput.tsx` on the
        # web and `FieldRenderer.kt` on the handset, whose own docstring says the caption is drawn
        # "INSIDE this field's block, directly under the media it describes". It is not on every
        # media field, hence the clause — a designer told to use a box that is not on their screen
        # stops reading these sentences.
        #
        # THE COUNT IS WALKED, NOT GREPPED, and the difference bit: `stage_definitions.py` mentions
        # `caption_for` on eleven SOURCE LINES, but one of them is inside a helper that emits the
        # pair for a whole family of media fields, so the registry actually carries 23. Counting the
        # lines and writing the number down is how a comment comes to state a measurement nobody
        # measured. Walk it instead:
        #     [f for s in stage_schema.stages() for e in s.entities for f in e.fields if f.caption_for]
        alternative="Write the description yourself in the caption box under the photograph, where "
        "the stage has one. ",
    ),
    "SUBTITLES": Send(
        material="material",
        destination="the transcription service",
        # "A RECORDING OR A VIDEO" FOR CAPTION'S REASON EXACTLY, and it said only "a recording"
        # until this was traced. `_verb_gate` runs before `_verb_source_media`, so at the moment
        # this sentence is composed the server has not looked at the file and genuinely does not
        # know which of the two it is — and `_VERB_MEDIA_TYPES["SUBTITLES"]` accepts both, with its
        # own refusal spelling them "a recording or a video". This server therefore had two names
        # for one file, one refusal apart: a designer subtitling a video was told a RECORDING could
        # not be subtitled, by the same server that would have called it a video had they picked an
        # audio-only verb. The narrower word is not safer — it is a different file.
        consequence="a recording or a video from it cannot be subtitled there",
        # No hand-timing anywhere in this app, and nothing false offered in place of one. "The file
        # is untouched" is deliberately about THIS action: whether that file already reached a
        # provider by some other path is not something this sentence may claim either way. And it
        # says "file" rather than "recording" so that it is true of the video case as well.
        alternative="Nothing was sent, and the file is untouched. ",
    ),
}

#: The recording ATTACHED to a workshop, as opposed to a passage dictated into a field.
#:
#: **THE SEND THIS MODULE DID NOT GOVERN, AND IT IS THE ONE THAT CARRIES THE MOST VOICE.** ``DICTATION``
#: above is seconds of a designer speaking into a form field; this is the interview — an artisan
#: explaining a technique for eleven minutes, uploaded through ``POST /media/complete`` and written
#: down by the very same ``ai.transcribe_audio_bytes`` chain. Until this constant existed the gate read
#: the consent column for the short one and not for the long one, which is the shape
#: ``POST /design-workshops/dictate`` was retired to a 410 for: a gate with a door beside it.
#:
#: THE ALTERNATIVE IS TRUE OF THIS SEND AND OF NO OTHER. A refused dictation loses the words; a refused
#: transcription loses only the writing-down, because the audio is kept with the workshop deliberately
#: — ``DW_CONSENT_QUESTION`` says so to the artisan in those terms ("a recording attached to this
#: workshop as audio is kept with the workshop, because it is there to be listened to again"). So the
#: recording is still there to be played, and the honest next move is a person listening to it.
MEDIA = Send(
    material="recordings",
    destination="the transcription service",
    consequence="this recording cannot be written down there",
    alternative="The recording is still kept with the workshop and can be listened to; write down "
    "what matters from it in the stage's own fields instead. ",
)

#: The transcript of a recording, on its way to a rewrite. ``POST /media/{id}/refine-transcript``.
#:
#: A SEPARATE DESCRIPTION BECAUSE THE DESTINATION IS A DIFFERENT COMPANY. The transcript goes to
#: OpenAI's chat model, not to the transcription service that produced it, and a refusal naming the
#: wrong one is the defect :class:`Send` was parameterised to end. The material is still the artisan's
#: words — ``_verb_gate``'s argument applies unchanged: *"a transcript is the artisan's words with the
#: audio compressed out of them, so posting one to OpenAI is the same export in a smaller shape."*
REFINEMENT = Send(
    material="recordings",
    destination="OpenAI's language model",
    consequence="this transcript cannot be rewritten there",
    alternative="Nothing was sent, and the transcript is exactly as it was. ",
)

#: What an unrecognised verb is described as. Vague on purpose and false about nothing.
#:
#: A sixth verb added without copy is caught by a test that walks ``ai_verbs.Verb`` against
#: :data:`SENDS`, so this should be unreachable. It exists because the alternative if it ever IS
#: reached is worse than vague: falling back to :data:`DICTATION` would put the dictation sentence in
#: front of a designer doing something else, which is the defect this whole value was written to close.
UNKNOWN_SEND = Send(
    material="material",
    destination="a third-party provider",
    consequence="this cannot be done there",
    alternative="Nothing was sent. ",
)


def send_for(verb: str | None) -> Send:
    """The send description for one AI verb. Never the dictation one for something that is not it."""
    token = str(getattr(verb, "value", verb) or "").upper()
    described = SENDS.get(token)
    if described is None:
        logger.warning(
            "dictation_consent: no send description for verb %r; the refusal will name neither the "
            "material nor the destination",
            token or verb,
        )
        return UNKNOWN_SEND
    return described


def gate_refusal(consent: DictationConsent, send: Send = DICTATION) -> str | None:
    """Why this workshop's material may not be sent to a provider, or None when it may.

    **A SENTENCE AND NEVER A CODE, and every one of them names a next move that can actually work.**
    Both refusals are shown to the designer verbatim — the Android control prints the server's own
    detail string for every HTTP answer that is not the route's own 503 — so this is field copy and
    not a log line. Neither says "try again", because trying again is incapable of a different
    outcome: what changes a consent is a person deciding.

    The two sentences are separate rather than one parameterised string because the next moves differ.
    NOT_RECORDED is answered by asking the artisan; REFUSED has already been asked and answered, and
    telling somebody to go and ask again when the answer is on record is the sort of instruction that
    teaches a designer to stop reading these messages. **That distinction is preserved for every
    send**, which is the whole point of the module: the two states must never collapse into one
    sentence, whatever is being sent.

    ``send`` DEFAULTS TO :data:`DICTATION` and the two strings it composes are byte for byte the two
    this function has always returned. That is not politeness to the tests — it is that a designer who
    has read the dictation refusal a hundred times should not find it reworded today, and
    ``POST /{id}/dictate`` and the handset's copy both depend on it.
    """
    if consent is DictationConsent.GRANTED:
        return None
    if consent is DictationConsent.REFUSED:
        return (
            f"This workshop's {send.material} may not be sent to {send.destination} — that is the "
            f"answer on record — so {send.consequence}. {send.alternative}If the artisan has since "
            f"agreed, change that answer on the workshop's own screen; nothing on this field can "
            f"change it."
        )
    return (
        f"Nobody has recorded yet whether {send.material} from this workshop may be sent to "
        f"{send.destination}, so {send.consequence}. {send.alternative}Open the workshop's own screen "
        f"and record the artisan's answer to that question — until somebody does, this stays "
        f"unavailable."
    )


# --------------------------------------------------------------------------------------
# The gate for STORED media: which workshop a recording belongs to, and whether it may be sent
#
# Everything above answers "may this workshop send" for a caller who is holding the workshop.
# Everything here answers the harder question the transcription queue actually asks: **whose workshop
# is this file, and is there an answer on record for it.**
# --------------------------------------------------------------------------------------


#: The ``MediaFile.linkedRecordType`` tag under which BOTH clients file every design-workshop
#: attachment, and therefore the link this server already has and never read.
#:
#: **THE LOOKUP EVERYBODY SAID WAS IMPOSSIBLE IS TWO COLUMNS THAT WERE ALREADY BEING SENT.** The reason
#: recorded for the queue not being gated is that ``MediaFile`` has no foreign key to
#: ``DesignWorkshop`` — which is true, and which was taken to mean the workshop cannot be known at
#: enqueue time. It can. Every design-workshop upload arrives carrying
#: ``linkedRecordType="designWorkshop"`` and ``linkedRecordId=<the DesignWorkshop id>``:
#: ``WorkshopRepository.uploadDesignWorkshopMedia`` on the handset (via its own
#: ``DESIGN_WORKSHOP_MEDIA_TAG``, whose docstring exists to keep the two spellings identical) and the
#: ``uploadMediaBatch`` call in ``frontend/lib/designWorkshopStore.ts``'s sync pass. Both columns are
#: persisted verbatim — ``records.media_relation_data`` has no column for this tag, so it contributes
#: nothing and the string survives untouched, and ``media._tagged_parent`` does not know the tag either.
#: So the id has been sitting on every workshop recording in the corpus, unread, the whole time.
#:
#: WHAT THAT BUYS THAT A NEW COLUMN WOULD NOT: nothing to migrate, nothing to backfill, and the
#: recordings ALREADY IN the database are covered from the moment this code runs rather than from the
#: moment somebody re-saves their stage. A nullable ``designWorkshopId`` would have been NULL for every
#: existing row, and under the fail-closed rule that means the archive's recordings become
#: un-transcribable until each one is touched.
#:
#: THE TAG IS CALLER-SUPPLIED, and that only fails safe. A bogus id under this tag does not load, which
#: refuses. A recording DE-tagged to evade the gate is no longer filed under the workshop at all — it
#: disappears from the workshop's own media screen — and :func:`transcription_verdict`'s
#: ``design_workshop_id`` argument catches it anyway on the path that matters, because the stage save
#: that attaches it to a stage names the workshop outright.
MEDIA_TAG = "designWorkshop"

#: The tag a QUESTIONNAIRE clip carries, written by both clients at upload time.
#:
#: ── WHY THIS MODULE NOW KNOWS ABOUT INTERVIEWS AT ALL ─────────────────────────────────────────
#: :func:`stage_attached_workshop_ids` says in as many words that of the 528 AUDIO rows on this
#: deployment, "279 of them are transcribed questionnaire material this consent says nothing about".
#: That was true and it stayed true for a reason worth keeping: a questionnaire interview is a
#: repository record in its own right, taken by researchers who are not running a design workshop at
#: all, and sweeping every one of them into a workshop's consent regime would have made the archive's
#: interviews un-transcribable until somebody answered a question about a workshop they were never
#: part of.
#:
#: WHAT CHANGED ON 2026-08-31 IS NOT THAT, AND THE DIFFERENCE IS THE WHOLE OF THE RULE.
#: ``QuestionnaireInterview.designWorkshopId`` is a nullable column the interview form fills in from a
#: picker, so an interview can now SAY it is a design workshop's material. Where it says so, the clip
#: is that workshop's material and the artisan's answer about that workshop governs it. Where it says
#: nothing — which is every interview in this deployment today, measured: ``SELECT count(*) FILTER
#: (WHERE "designWorkshopId" IS NOT NULL) FROM "QuestionnaireInterview"`` answers 0 of 99 — the verdict
#: is exactly what it has always been, :data:`SendDecision.NOT_WORKSHOP_MATERIAL`, and nothing already
#: in the repository changes.
#:
#: ── AND WITHOUT IT THE NEW SYNCHRONOUS GATE WOULD BE THEATRE ─────────────────────────────────
#: The questionnaire page now posts a voice note to ``POST /design-workshops/{id}/dictate`` for an
#: immediate transcript, which IS gated. The very same bytes then go through ``/media/complete`` into
#: the queue. With only the tag read, a clip on a REFUSED workshop was refused at the microphone and
#: handed to ElevenLabs by the drain two hours later — one artisan's voice, one consent answer, two
#: opposite outcomes, and the second one silent. A gate that one of two paths honours is not a gate.
INTERVIEW_TAG = "questionnaire"


async def interview_workshop_id(media: Any) -> str | None:
    """The design workshop a questionnaire clip's own INTERVIEW names, or None.

    THE FOURTH WAY A RECORDING BELONGS TO A WORKSHOP, and unlike
    :func:`stage_attached_workshop_ids` it is not a scan: the link is on the row being gated, so this
    is one lookup by primary key and it is issued only for rows that are questionnaire material.
    Every other media row — a stage photograph, a miscellaneous upload, an artisan portrait — returns
    at the first branch having touched no database at all.

    IT READS THE TYPED FK FIRST AND THE FREE-TEXT TAG SECOND. ``MediaFile.questionnaireInterviewId``
    is set by ``media_relation_data`` on the server from the same request that carries the tag, so it
    is the more trustworthy of the two; the tag is the fallback for a row that predates the FK or was
    attached by a path that only wrote the pair. :func:`tagged_workshop_id`'s case-insensitive
    comparison is reused for the same reason it gives — the tag is a free string nothing normalises,
    and matching loosely can only ever ADD a consent check.

    **A FAILED READ RAISES RATHER THAN RETURNING None, and that is the opposite of what it looks
    like.** None here means "this interview names no workshop", which resolves to
    NOT_WORKSHOP_MATERIAL and therefore to a SEND. So swallowing an exception into None would turn a
    database blink into permission, which is the one direction this module's fail-closed rule forbids.
    Raising leaves the question unanswered instead of guessed: the caller's ordinary retry ladder
    handles it and nothing is sent either way — the identical ruling, for the identical reason, that
    :func:`stage_attached_workshop_ids` records at length for its own read.
    """
    interview_id = str(_attr(media, "questionnaireInterviewId") or "").strip()
    if not interview_id:
        tag = str(_attr(media, "linkedRecordType") or "").strip().lower()
        if tag != INTERVIEW_TAG.lower():
            return None
        interview_id = str(_attr(media, "linkedRecordId") or "").strip()
    if not interview_id:
        return None
    row = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    if row is None:
        # The interview is gone. That is not a refusal: a clip whose parent was deleted is an orphan,
        # and orphans are exactly what `NOT_WORKSHOP_MATERIAL` has always meant for this tag. A
        # workshop it might once have named cannot be recovered from here, and inventing a refusal
        # would strand the recording permanently — `_finalize_refused_job` does not retry.
        return None
    return str(getattr(row, "designWorkshopId", None) or "").strip() or None


class SendDecision(str, Enum):
    """What this server was able to establish about one stored file, in three states and not two.

    THE THIRD STATE IS THE WHOLE REASON THIS IS NOT A BOOLEAN, and collapsing it is how a fail-closed
    rule turns into a broken repository. "No answer on record" and "not this feature's business" both
    produce *no consent to read*, and they must produce opposite outcomes: the first stops a send, the
    second is a questionnaire interview recorded in 2024 that has always been transcribed and whose
    artisan was asked a different question by a different consent process. A boolean would have had to
    pick one meaning for both, and either choice is a defect — refuse everything and the interview
    pipeline stops; allow everything and the gate is ornamental again.
    """

    #: No design workshop is named by this file and none was supplied. Nothing in this module governs
    #: it and its behaviour is exactly what it was before the gate existed.
    NOT_WORKSHOP_MATERIAL = "NOT_WORKSHOP_MATERIAL"
    #: A design workshop, and its recorded answer is GRANTED.
    GRANTED = "GRANTED"
    #: A design workshop whose answer is not GRANTED, or a workshop this server could not read at all.
    REFUSED = "REFUSED"


@dataclass(frozen=True, slots=True)
class SendVerdict:
    """The answer, the workshop it is about, and the sentence to show if it is no.

    ``may_send`` IS THE ONLY THING A CALLER SHOULD BRANCH ON, and it is a method on the verdict rather
    than a bare boolean the caller computes, so that "not workshop material" can never be written as
    ``decision is GRANTED`` by somebody who then silently stops transcribing every interview in the
    repository. The two states that permit a send are permitted for opposite reasons and the verdict
    keeps both reasons legible.
    """

    decision: SendDecision
    workshop_id: str | None = None
    refusal: str | None = None

    def __post_init__(self) -> None:
        if (self.decision is SendDecision.REFUSED) != bool(self.refusal):
            raise ConsentRuleViolation(
                "A refused send must carry the sentence that will be shown, and a permitted one must "
                "carry none. A refusal with nothing to say reaches a designer as a blank."
            )

    @property
    def may_send(self) -> bool:
        return self.decision is not SendDecision.REFUSED


class SendRefused(Exception):
    """A send consent does not permit, raised where a returned verdict would be dropped on the floor.

    WHY AN EXCEPTION EXISTS AT ALL when :class:`SendVerdict` is the normal answer. The queue can act on
    a verdict — it simply does not create the job — but ``media_queue.transcribe_media_now`` is a
    SYNCHRONOUS transcription with an admin waiting on its result, and its documented contract is that
    it *"never raises on an AI failure"*: it writes the outcome onto the media row and returns it. A
    verdict returned from inside that function has nowhere to go except into a result dict that the
    caller reads as an AI outcome, which would report a consent refusal as a transcription failure.

    Raised rather than returned so that a route which forgets to inspect the result cannot send anyway.
    The route turns it into a 409 with this sentence — the same status and the same wording discipline
    as ``POST /design-workshops/{id}/dictate``, because it is the same refusal about the same workshop.
    """

    def __init__(self, verdict: SendVerdict) -> None:
        super().__init__(verdict.refusal or "This send is not permitted.")
        self.verdict = verdict


def tagged_workshop_id(media: Any) -> str | None:
    """The design workshop one stored media row names through its link tag, or None.

    Pure, so the whole classification is assertable with no database: given a row-shaped object it
    answers which workshop the file was filed under. The tag is compared case-insensitively after a
    strip, because it is a free string on ``MediaFile`` that nothing normalises — the handset constant's
    own docstring records that two spellings would file one workshop's photographs in two buckets.
    Matching loosely here is not laxity: every spelling this recognises leads to a workshop id that is
    then either loaded or refused, so a match can only ever ADD a consent check.
    """
    tag = str(_attr(media, "linkedRecordType") or "").strip().lower()
    if tag != MEDIA_TAG.lower():
        return None
    return str(_attr(media, "linkedRecordId") or "").strip() or None


def verdict_for(
    consent: DictationConsent | None,
    *,
    workshop_id: str | None,
    send: Send = MEDIA,
) -> SendVerdict:
    """One file's verdict from a consent already read. Pure, and the fail-closed rule lives here.

    ``workshop_id`` of None means no workshop was named — :data:`SendDecision.NOT_WORKSHOP_MATERIAL`,
    and the caller behaves as it always did.

    ``consent`` of None means a workshop WAS named and could not be read: it does not exist, it is
    deleted, or the read failed. **That refuses**, and it is the case the whole module docstring's
    fail-closed argument is about — *"an unknown pack costs a moment; an unknown consent costs a named
    artisan's recorded voice leaving the device for a third party"*. The sentence it produces is
    NOT_RECORDED's, which is the true one: this server has no answer on record for that workshop.
    """
    if not workshop_id:
        return SendVerdict(SendDecision.NOT_WORKSHOP_MATERIAL)
    refusal = gate_refusal(consent or DictationConsent.NOT_RECORDED, send)
    if refusal is None:
        return SendVerdict(SendDecision.GRANTED, workshop_id=workshop_id)
    return SendVerdict(SendDecision.REFUSED, workshop_id=workshop_id, refusal=refusal)


def _attr(record: Any, key: str) -> Any:
    """One field of a row that may be a Prisma model or a plain dict. ``media_queue._value``'s twin."""
    if isinstance(record, Mapping):
        return record.get(key)
    return getattr(record, key, None)


# --------------------------------------------------------------------------------------
# Write plans: the only way this module can change anything
# --------------------------------------------------------------------------------------


class Operation(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"


@dataclass(frozen=True, slots=True)
class ConsentWritePlan:
    """One intended database write, described rather than performed.

    WHY A PLAN AND NOT A COROUTINE THAT WRITES, in the two words ``ai_layers.LayerWritePlan`` already
    argues at length: a plan can be asserted about by ``pytest`` with no database, no event loop and
    no generated Prisma client, and a plan names its TABLE — which is what makes the "consent is never
    a stage field" argument true by construction rather than by convention.
    """

    table: str
    operation: Operation
    data: Mapping[str, Any]
    #: Present for an UPDATE only, and always exactly ``{"id": ...}`` — one row, named.
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in WRITABLE_TABLES:
            raise ConsentRuleViolation(
                f"A dictation consent may not be written into {self.table}. It is three columns on "
                f"the workshop and a row in its decision log, and nowhere else: a stage field cannot "
                f"say WHO recorded it or WHEN — save_stage writes only data, ordinal and deletedAt, "
                f"and a stage row's updatedAt moves whenever anything in that stage changes. Write it "
                f"to one of {', '.join(sorted(WRITABLE_TABLES))}."
            )
        if self.operation is Operation.UPDATE and not self.where:
            raise ConsentRuleViolation(
                "An update must name the single row it changes. Pass where={'id': workshop_id}."
            )
        if self.operation is Operation.CREATE and self.where:
            raise ConsentRuleViolation("A create names no existing row. Drop the where clause.")


@dataclass(frozen=True, slots=True)
class ConsentDecisionPlans:
    """The two writes one consent decision makes: the answer on the workshop, and the row in the log.

    Returned together and applied together, exactly as ``ai_layers.DecisionPlans`` is. The log is the
    authoritative history — it is what keeps "who cleared this workshop's recordings on the 3rd"
    answerable after a withdrawal on the 9th, by which time transcripts made under the grant are
    already in the record — and the columns are the current state the gate reads without walking a log
    per dictation.
    """

    workshop: ConsentWritePlan
    decision: ConsentWritePlan

    def __iter__(self):
        yield self.workshop
        yield self.decision


def decision_plans(
    *,
    workshop_id: str,
    decision: DictationConsent,
    actor_id: str,
    at: datetime,
    recorded_at: datetime | None = None,
    note: str | None = None,
) -> ConsentDecisionPlans:
    """A person records an artisan's answer for one workshop.

    ``at`` is the server's clock — when this request arrived. ``recorded_at`` is what the DEVICE said,
    and when it is present it is the moment the artisan actually answered; a consent recorded offline
    in a courtyard reaches the server on the next sync, which can be a fortnight later.

    **WHAT LANDS IN ``dictationConsentAt`` IS THE MOMENT THE ARTISAN ANSWERED**, so ``recorded_at``
    when it was supplied and ``at`` when it was not. The other question — when the server heard it —
    is answered by the log row's own ``createdAt`` default, which is why nothing here sets it.

    ``NOT_RECORDED`` IS REFUSED AS A DECISION, and that is not pedantry: it is the absence of an
    answer, and "somebody deliberately recorded that nobody has been asked" is not a state a person
    can be in. A designer who wants to take an answer back records REFUSED, which is a decision with a
    next move; unrecording one would leave a log saying an answer was un-given and a gate that cannot
    tell that from a workshop nobody has opened.
    """
    workshop = _require_text(
        workshop_id,
        what="A consent belongs to a workshop.",
        remedy="Send the id of the workshop whose recordings this answer is about.",
    )
    actor = _require_text(
        actor_id,
        what="A consent records who took the answer down.",
        remedy=(
            "Sign in and record it as yourself — this is the row that says a named person cleared an "
            "artisan's voice to leave the device, and an unsigned one is worth nothing."
        ),
    )
    if decision is DictationConsent.NOT_RECORDED:
        raise ConsentRuleViolation(
            "NOT_RECORDED is what a workshop says before anybody has asked, not an answer somebody "
            "can record. Send GRANTED if the artisan agreed, or REFUSED if they did not — a REFUSED "
            "answer is how a consent is taken back."
        )
    # BOTH MOMENTS ARE MADE TIMEZONE-AWARE BEFORE EITHER IS COMPARED OR STORED, and the reason is a
    # 500 rather than a preference. An ISO-8601 moment with no offset — "2026-08-11T16:40:00", which
    # `datetime.fromisoformat` accepts and a client can legitimately send — parses to a NAIVE datetime,
    # and `naive > aware` raises TypeError in Python. That TypeError is not a `ConsentRuleViolation`,
    # so the route's except clause does not catch it and the designer recording an artisan's answer in
    # a courtyard gets "something went wrong on the server" for a consent they were entitled to file.
    # A naive moment is read as UTC, exactly as `dictation_cap.ist_day` and the route's own
    # `_parse_datetime` already read one, and for that function's stated reason: shifting a naive
    # datetime by an offset is only right if it happened to be UTC, and reading it as UTC is the one
    # assumption the rest of this repository already makes out loud.
    at = _as_utc(at)
    recorded_at = _as_utc(recorded_at)
    if recorded_at is not None and recorded_at > at + MAX_DEVICE_CLOCK_SKEW:
        raise ConsentRuleViolation(
            f"This answer says it was recorded at {recorded_at.isoformat()}, which is in the future — "
            f"the device's clock is wrong. Fix the date and time on the phone and sync again, or "
            f"record the answer here so the server's own clock is used. It is not stored with a "
            f"corrected time, because when somebody consented is not something this server may guess."
        )

    answered_at = recorded_at or at
    return ConsentDecisionPlans(
        workshop=ConsentWritePlan(
            table="DesignWorkshop",
            operation=Operation.UPDATE,
            where={"id": workshop},
            data={
                "dictationConsent": decision.value,
                "dictationConsentAt": answered_at,
                "dictationConsentById": actor,
            },
        ),
        decision=ConsentWritePlan(
            table="DwWorkshopConsentDecision",
            operation=Operation.CREATE,
            data={
                "designWorkshopId": workshop,
                "decision": decision.value,
                "note": (note or "").strip() or None,
                "actorId": actor,
                # Only what the DEVICE said. Null when the answer was recorded straight against the
                # server, where `createdAt` is the same moment and repeating it would add nothing —
                # and where a copy would later read as "a device reported this", which is false.
                "recordedAt": recorded_at,
            },
        ),
    )


def _as_utc(moment: datetime | None) -> datetime | None:
    """A moment that can be compared with another. Naive means UTC; aware is left alone.

    Not a conversion to UTC: an aware moment keeps its own offset, because `+05:30` is what the phone
    said and rewriting it would lose the only clue about where the answer was taken down. All this
    does is refuse to let a missing offset turn into a `TypeError` at the comparison two lines above
    the refusal it would otherwise have produced.
    """
    if moment is None or moment.tzinfo is not None:
        return moment
    return moment.replace(tzinfo=UTC)


def _require_text(value: str | None, *, what: str, remedy: str) -> str:
    text = (value or "").strip()
    if not text:
        raise ConsentRuleViolation(f"{what} {remedy}")
    return text


# --------------------------------------------------------------------------------------
# The wire
# --------------------------------------------------------------------------------------


def consent_keys(record: Any) -> dict[str, Any]:
    """The three keys every client reads a workshop's consent from. camelCase on the wire.

    Added to ``workshop_summary``, which is a hand-written dict over the record, so a column reaches a
    client only by being named here.

    **THE ACCEPTOR'S DISPLAY NAME IS NOT ONE OF THEM**, deliberately. It is resolved in the
    single-record read alone, as ``dictationConsentByName``: the workshop LIST serialises this dict
    once per row, and looking a name up per row would put a query per workshop into a paged endpoint
    to print something the list does not show. The id is what a list needs — it is enough to know
    whether the consent was recorded by the account looking at it.

    ``dictationConsent`` is always a string from the enum and never null, so a client can render a
    state machine rather than inferring one from an absence. The read goes through
    :func:`consent_of`, so an unreadable stored token reaches the clients as ``NOT_RECORDED`` — the
    same fail-closed answer the gate uses, rather than a raw token no client has a branch for.
    """
    return {
        "dictationConsent": consent_of(record).value,
        "dictationConsentAt": _iso(getattr(record, "dictationConsentAt", None)),
        "dictationConsentById": getattr(record, "dictationConsentById", None),
    }


def decision_payload(row: Any) -> dict[str, Any]:
    """One recorded consent answer as the clients read it.

    Both moments are carried, and that is the point of the pair rather than a duplication:
    ``recordedAt`` is what the device said (null when the answer was taken straight against the
    server) and ``createdAt`` is when the server heard it. A fortnight of no signal makes them differ
    by a fortnight, and a reader who can see only one of them cannot tell a consent given today from
    one given before the workshop was synced.
    """
    return {
        "id": getattr(row, "id", None),
        "designWorkshopId": getattr(row, "designWorkshopId", None),
        "decision": _enum_str(getattr(row, "decision", None)),
        "note": getattr(row, "note", None),
        "actorId": getattr(row, "actorId", None),
        "recordedAt": _iso(getattr(row, "recordedAt", None)),
        "createdAt": _iso(getattr(row, "createdAt", None)),
    }


def _enum_str(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


# --------------------------------------------------------------------------------------
# The database half: the plan executor and the two reads
#
# Everything above this line is pure and is what tests/test_dictation_consent_and_cap.py exercises.
# Everything below is a thin call site: it applies a plan built above, or it reads rows. No rule is
# decided here.
# --------------------------------------------------------------------------------------


def _writable_model(table: str) -> Any:
    """The Prisma model one writable table name maps to.

    THE SECOND HALF OF THE CONSTRUCTION GUARD, and the name is checked before the client is touched so
    the refusal is a :class:`ConsentRuleViolation` with a sentence on any machine, with or without a
    generated Prisma client. There is no entry for ``DwStageEntry``, so even a plan that somehow
    carried its name — a future edit that loosened :class:`ConsentWritePlan` — would still have nowhere
    to be applied. Resolved per call rather than in a module-level dict, because a dict built at import
    binds the client attributes before anything has connected.
    """
    if table not in WRITABLE_TABLES:
        raise ConsentRuleViolation(
            f"There is no consent writer for {table}. A consent is three columns on the workshop and "
            f"a row in its decision log: {', '.join(sorted(WRITABLE_TABLES))}."
        )
    return {
        "DesignWorkshop": db.designworkshop,
        "DwWorkshopConsentDecision": db.dwworkshopconsentdecision,
    }[table]


async def apply_plan(plan: ConsentWritePlan) -> Any:
    """Perform one planned write. The only place this module touches the database with intent."""
    model = _writable_model(plan.table)
    if plan.operation is Operation.CREATE:
        return await model.create(data=dict(plan.data))
    return await model.update(where=dict(plan.where or {}), data=dict(plan.data))


async def apply_decision(plans: ConsentDecisionPlans) -> Any:
    """Apply a decision's two writes: the workshop's answer first, then the log entry.

    NOT IN A TRANSACTION, and the order is chosen for the failure that leaves — the same reasoning
    ``ai_layers.apply_decision`` records, with one difference that decides it the same way. If the log
    write fails after the workshop write, the workshop carries a correctly attributed answer with one
    missing history row: recoverable, and the gate is right. The other order would leave a log saying
    somebody cleared this workshop while the gate still refuses — or, far worse on a REFUSED, a log
    saying consent was withdrawn while the column still says GRANTED and sends continue. Prisma's
    Python client does expose an interactive transaction; using one here is a reasonable later change,
    and this ordering makes the worst case survivable rather than dangerous in the meantime.
    """
    workshop = await apply_plan(plans.workshop)
    await apply_plan(plans.decision)
    return workshop


async def stage_attached_workshop_ids(media_id: str) -> list[str]:
    """Every design workshop whose live stage entries name this media id in an AUDIO field.

    **THE THIRD WAY A RECORDING BELONGS TO A WORKSHOP, AND THE ONE THAT LEFT A HOLE MEASURED ON THE
    WIRE.** :func:`tagged_workshop_id` reads the tag the clients send at upload time, and
    ``design_workshop_id`` is the id a caller is already holding. Neither is available on the path that
    matters most: ``POST /media/complete`` for a clip with no tag creates a TRANSCRIPTION job with no
    workshop anywhere in sight, and the clip is written into a stage field only afterwards — the phone
    uploads the file and then saves the stage, which is the ordinary order of events. From that moment
    the recording IS workshop material and the stage says so, but nothing the gate read could see it.
    Reproduced against the running API: a clip uploaded untagged and then attached as ``artisanAudio``
    on a workshop whose ``dictationConsent`` is REFUSED was handed to ElevenLabs, Deepgram and OpenAI
    by the queue drain, by ``POST /media/{id}/transcribe-now`` and — as text — by
    ``POST /media/{id}/refine-transcript``, all three of which read only the tag.

    **WALKED THROUGH THE AUDIO FIELDS AND NOT THROUGH THE WHOLE ROW.** ``audio_field_map`` is the same
    registry walk ``workshop_transcripts.audio_references`` uses to decide what gets transcribed in the
    first place, so the set that is GATED cannot drift from the set that is QUEUED. It also keeps a TEXT
    field that happens to hold something id-shaped from being read as a recording, which is
    ``audio_field_map``'s own stated reason for existing.

    ONE STATEMENT, NARROWED BY ``entityKey`` — which ``DwStageEntry_entityKey_idx`` serves — because
    this runs once per transcription job on a path that is about to spend a provider round trip. It is
    a raw query for the reason ``design_workshops._reference_photos`` is one: Prisma's Python client
    cannot express "this jsonb value, or any element of it, equals that string". ``@>`` covers both
    shapes at once, so a field holding one id and a field holding a list of them are matched by the
    same clause. Soft-deleted entries are excluded: a stage row a designer has removed no longer says
    that this workshop holds the recording.

    **IT DOES NOT CATCH ITS OWN FAILURE, and that is the considered choice.** A refusal here would be
    terminal — ``media_queue._finalize_refused_job`` does not retry — so failing closed on a broken
    read would permanently kill the transcription of every untagged INTERVIEW clip in the repository
    (462 of the 528 AUDIO rows on this deployment carry no tag, and 279 of them are transcribed
    questionnaire material this consent says nothing about) and would write a sentence about a workshop
    onto rows that have none. Failing open would restore the hole above. So the question is left
    unanswered instead of guessed: the exception reaches the caller, the queue's ordinary retry ladder
    handles it, and **nothing is sent either way**.
    """
    media_id = str(media_id or "").strip()
    if not media_id:
        return []
    from app.services.workshop_transcripts import audio_field_map

    field_map = audio_field_map()
    entity_keys = sorted(field_map)
    field_keys = sorted({key for fields in field_map.values() for key in fields})
    if not entity_keys or not field_keys:
        return []
    rows = await db.query_raw(
        'SELECT DISTINCT e."designWorkshopId" AS id FROM "DwStageEntry" e '
        'WHERE e."deletedAt" IS NULL AND e."entityKey" = ANY($2::text[]) AND EXISTS ('
        '  SELECT 1 FROM jsonb_each(e."data") kv'
        "  WHERE kv.key = ANY($3::text[]) AND kv.value @> to_jsonb($1::text))",
        media_id,
        entity_keys,
        field_keys,
    )
    return sorted({str(row["id"]) for row in rows if row.get("id")})


async def transcription_verdict(
    media: Any,
    *,
    design_workshop_id: str | None = None,
    send: Send = MEDIA,
    resolve_from_stages: bool = False,
) -> SendVerdict:
    """Whether one STORED recording may be sent to a provider. The queue's gate, and the only one.

    ``design_workshop_id`` IS FOR THE CALLER THAT ALREADY KNOWS.
    ``workshop_transcripts.enqueue_stage_transcriptions`` is called from ``save_stage``, which is
    holding the workshop id in a path parameter — so a clip attached to a stage is gated even when it
    was uploaded through the generic media picker and carries no tag at all. That is the case a tag
    alone would miss, and it is not hypothetical: a designer can paste any media id into a stage field.

    **IT DOES NOT WIN OVER THE ROW'S OWN TAG ANY MORE — EVERY WORKSHOP THAT NAMES THIS FILE HAS TO
    PERMIT THE SEND, AND ONE REFUSAL IS THE ANSWER.** Precedence was the defect: with the caller's id
    overriding the tag, ``PUT /design-workshops/{A}/stages/{k}`` reported ``transcriptionsQueued: 1``
    for a recording tagged to workshop **B** — measured on the running API, A GRANTED and B REFUSED —
    because the gate read the workshop in the URL rather than the workshop whose artisan the recording
    is of. A designer running two workshops in one cluster owns the media of both, so pasting the wrong
    id into the wrong stage is an ordinary slip, and the consent that governs a voice is the consent of
    the person whose voice it is. Refusing when the two disagree is also the only reading that cannot be
    used deliberately: there is no id a caller can supply that turns a REFUSED tag into a send.

    ``resolve_from_stages`` adds the third source — :func:`stage_attached_workshop_ids` — and is OFF by
    default. It is for the callers holding a row that may have been attached to a stage since it was
    stored: the queue drain, "transcribe now", and transcript refinement. ``POST /media/complete`` does
    not set it, because a row created in that request cannot yet be named by any stage and the scan
    would be spent on every upload to learn nothing. The scan runs only when neither of the other two
    sources named a workshop, i.e. exactly in the blind spot.

    :func:`interview_workshop_id` is the FOURTH source and it is always consulted, unlike the third.
    A questionnaire clip's interview may name a design workshop, and where it does the clip is that
    workshop's material; where it does not — every interview in this deployment today — the answer is
    unchanged from what it has always been. Read that function's own note for why this could not be
    left out once the questionnaire page began posting the same audio to the gated dictation route.

    THE CONSENT READ IS ONE INDEXED LOOKUP BY PRIMARY KEY PER CANDIDATE, which is what makes it
    affordable in the place it has to be affordable — the queue drain, once per job, and the stage save,
    once per clip. In the overwhelmingly common case there is exactly one candidate.

    **A FAILED CONSENT READ REFUSES, and it is a ``return`` rather than a raise.** The queue's callers
    are a background worker and a designer's stage save; an exception in either becomes a lost job or a
    failed save, and neither is the right answer to "the database blinked". The refusal is the right
    answer: nothing is sent, the clip stays a candidate for the next pass, and no consent was guessed.
    ``deletedAt`` is NOT filtered — a soft-deleted workshop still holds the artisan's answer, and
    reading it is how a recording in a deleted workshop stays refused rather than becoming unreadable
    and therefore, under the rule above, refused for the wrong reason.
    """
    about = _attr(media, "id")
    candidates: list[str] = []
    for candidate in (str(design_workshop_id or "").strip(), tagged_workshop_id(media)):
        if candidate and candidate not in candidates:
            candidates.append(candidate)
    # THE INTERVIEW'S OWN ANSWER, ADDED RATHER THAN PREFERRED. It joins the candidate list under the
    # same rule as everything else above — every workshop that names this file has to permit the send
    # and one refusal is the answer — so a clip filed under workshop A and interviewed for workshop B
    # cannot be released by whichever source happens to be read first. It is NOT behind
    # ``resolve_from_stages``: that flag exists because a stage scan is a query over a table the row
    # does not point at, whereas this link is a column ON the row and is already there at
    # ``POST /media/complete`` time, which is the one place a questionnaire clip is enqueued.
    interview_workshop = await interview_workshop_id(media)
    if interview_workshop and interview_workshop not in candidates:
        candidates.append(interview_workshop)
    if not candidates and resolve_from_stages:
        candidates = await stage_attached_workshop_ids(str(about or ""))
    if not candidates:
        return SendVerdict(SendDecision.NOT_WORKSHOP_MATERIAL)
    permitted: SendVerdict | None = None
    for workshop_id in candidates:
        verdict = await workshop_send_verdict(workshop_id, send=send, about=about)
        if not verdict.may_send:
            return verdict
        permitted = permitted or verdict
    return permitted or SendVerdict(SendDecision.NOT_WORKSHOP_MATERIAL)


async def workshop_send_verdict(
    workshop_id: str | None,
    *,
    send: Send = MEDIA,
    about: Any = None,
) -> SendVerdict:
    """One workshop's verdict, read from the database. ``about`` names the subject in the log only.

    Separate from :func:`transcription_verdict` so a caller that is holding a workshop and NO file can
    ask the same question and get the same sentence — ``save_stage`` wants to tell a designer why their
    recordings were not queued, and it has the workshop id but no single clip to name. Two spellings of
    this read would be two chances for the screen's explanation and the queue's decision to disagree,
    which is worse than no explanation at all: a designer told transcription is running while it is
    refused stops trusting the message rather than the feature.
    """
    workshop_id = str(workshop_id or "").strip()
    if not workshop_id:
        return SendVerdict(SendDecision.NOT_WORKSHOP_MATERIAL)
    try:
        row = await db.designworkshop.find_unique(where={"id": workshop_id})
    except Exception as exc:  # noqa: BLE001 — see the docstring: a failed read refuses, never raises.
        logger.warning(
            "dictation_consent: could not read the consent of workshop %s (subject %s): %s; "
            "refusing the send",
            workshop_id,
            about,
            exc,
        )
        return verdict_for(None, workshop_id=workshop_id, send=send)
    return verdict_for(
        consent_of(row) if row is not None else None, workshop_id=workshop_id, send=send
    )


async def workshop_audio_media_ids(workshop_id: str) -> list[str]:
    """Every recording this workshop holds, from BOTH of the two ways one can be reached.

    WHAT IT IS FOR: withdrawal. A consent taken back on the 9th has to reach the recordings queued
    under the grant given on the 3rd, and those jobs name a ``mediaFileId`` and nothing else — there is
    no column joining a job to a workshop.

    **TWO SOURCES, AND THE SECOND ONE WAS FOUND BY THE WIRE AND NOT BY A TEST.** The stage walk alone
    looked sufficient and is not, which a live probe showed within a minute: a recording uploaded with
    the ``designWorkshop`` tag is queued by ``/media/complete`` BEFORE any stage references it — that is
    the ordinary order of events, since the phone uploads the file and only then writes the id into the
    stage — so a withdrawal in that window found nothing to cancel and the clip sat reading QUEUED. The
    drain check would still have refused it, so nothing was ever going to be sent; what was broken was
    the VISIBILITY this function exists for, which is the half a designer can see.

    1. **The stage references** — the same walk ``workshop_transcripts.enqueue_stage_transcriptions``
       uses to find them in the first place, called through that module rather than reimplemented so the
       set that gets cancelled cannot drift from the set that gets queued.
    2. **The link tag** — ``linkedRecordType``/``linkedRecordId``, which is what the gate itself reads
       and is served by MediaFile's existing ``@@index([linkedRecordType, linkedRecordId])``. It catches
       the uploaded-but-not-yet-attached case, and it is deliberately NOT narrowed to ``mediaType
       AUDIO``: an id that is not a recording simply matches no transcription job.

    Each source is tried independently, so a failure in one still cancels what the other found — the
    alternative is a withdrawal that cancels nothing because an unrelated read raised. Imported inside
    the function because ``workshop_transcripts`` imports ``report_annexures``, which imports the report
    model; a module-level import would drag the whole report layer into every consent read, and into
    ``media_queue`` behind it.
    """
    from app.services.design_workshops import entry_rows
    from app.services.workshop_transcripts import audio_references

    found: set[str] = set()
    try:
        found |= set(audio_references(await entry_rows(workshop_id)))
    except Exception as exc:  # noqa: BLE001 — a withdrawal must be recorded even if a walk fails.
        logger.warning(
            "dictation_consent: could not walk workshop %s's stages for its recordings (%s)",
            workshop_id,
            exc,
        )
    try:
        tagged = await db.mediafile.find_many(
            where={"linkedRecordType": MEDIA_TAG, "linkedRecordId": workshop_id}
        )
        found |= {str(row.id) for row in tagged}
    except Exception as exc:  # noqa: BLE001 — same rule.
        logger.warning(
            "dictation_consent: could not read workshop %s's tagged media (%s)", workshop_id, exc
        )
    return sorted(found)


async def cancel_pending_transcriptions(workshop_id: str) -> int:
    """Stop the transcriptions already queued for this workshop. Returns how many were stopped.

    **THIS IS WHAT MAKES A WITHDRAWAL A WITHDRAWAL.** The module docstring's own justification for
    keeping a decision log is the artisan who *"agrees on the 3rd, nine dictations go to a provider over
    the following week, [and] changes their mind on the 9th"*. Recording REFUSED closes the gate against
    future sends — but a recording queued on the 3rd is a row sitting in ``MediaProcessingJob`` waiting
    for the off-peak window, and without this it would still go out on the night of the 9th, after the
    artisan said stop. A consent that cannot recall what it already authorised is a preference, not a
    permission.

    THE QUEUE DRAIN RE-READS CONSENT TOO (``media_queue._process_job``), so this is the second of two
    defences rather than the only one — and both are wanted. The drain check is what holds if this call
    fails or if a job is created by some path not yet imagined; this call is what makes the withdrawal
    VISIBLE, immediately, on the transcripts screen the designer is looking at, instead of leaving nine
    clips reading QUEUED for hours while the truth is that none of them will ever run.

    NEVER RAISES, and the caller does not check the count. A withdrawal must be recorded even when the
    cancellation cannot be — the recorded answer is the artisan's and is what the gate reads, and losing
    it because a cleanup query failed would be the worst possible trade. What is left in that case is a
    set of jobs the drain will refuse one by one, which is the correct outcome by a slower route.
    """
    media_ids = await workshop_audio_media_ids(workshop_id)
    if not media_ids:
        return 0
    try:
        stopped = await db.mediaprocessingjob.update_many(
            where={
                "mediaFileId": {"in": media_ids},
                "jobType": "TRANSCRIPTION",
                "status": {"in": ["QUEUED", "PROCESSING"]},
            },
            data={
                "status": "FAILED",
                "lockedAt": None,
                "lockedBy": None,
                "completedAt": datetime.now(UTC),
                # PROCESSING is included in the match above and cannot be un-sent: a worker holding
                # that lock may already be mid-round-trip. Cancelling the row is still right — it stops
                # the RESULT being written and stops any retry — and the sentence says only what is
                # true, which is that the consent was withdrawn, not that nothing reached a provider.
                "error": (
                    "Consent for this workshop's recordings was withdrawn, so this transcription was "
                    "cancelled before it ran. Recording the artisan's answer again as GRANTED, and "
                    "re-saving the stage the recording is attached to, is what queues it afresh."
                ),
            },
        )
    except Exception as exc:  # noqa: BLE001 — see the docstring: the recorded answer comes first.
        logger.warning(
            "dictation_consent: consent for workshop %s was withdrawn but its queued transcriptions "
            "could not be cancelled (%s); the queue will refuse them individually at send time",
            workshop_id,
            exc,
        )
        return 0
    if stopped:
        # The clips themselves, so the transcripts screen stops saying QUEUED. FAILED for
        # `media_queue._record_transcription_refused`'s reason: it is the one status
        # `workshop_transcripts._SETTLED_TRANSCRIPT_STATUSES` leaves eligible, so a later GRANTED
        # answer picks these recordings up again on the next save of their stage.
        try:
            await db.mediafile.update_many(
                where={
                    "id": {"in": media_ids},
                    "transcriptStatus": {"in": ["QUEUED", "PROCESSING"]},
                },
                data={
                    "transcriptStatus": "FAILED",
                    "transcriptError": gate_refusal(DictationConsent.REFUSED, MEDIA),
                },
            )
        except Exception as exc:  # noqa: BLE001 — the jobs are already stopped; this is the note.
            logger.warning(
                "dictation_consent: cancelled %s transcription job(s) for workshop %s but could not "
                "mark the recordings (%s)",
                stopped,
                workshop_id,
                exc,
            )
    return stopped


async def workshop_decisions(workshop_id: str) -> list[Any]:
    """The consent history of one workshop, oldest first."""
    return await db.dwworkshopconsentdecision.find_many(
        where={"designWorkshopId": workshop_id}, order={"createdAt": "asc"}
    )


async def actor_name(user_id: str | None) -> str | None:
    """The display name of whoever recorded the current answer, for the single-record read only.

    None when nobody has, and None when the account has since been deleted — the pointer is SetNull,
    so a workshop can legitimately carry an answer with no name against it, and the honest rendering
    of that is "cleared by somebody no longer on record" rather than a guess at the workshop's owner.
    That is the failure ``_export_payload`` already names for a deleted export author: the tempting
    default puts a name against something they never did.
    """
    if not user_id:
        return None
    row = await db.user.find_unique(where={"id": user_id})
    return getattr(row, "name", None) if row is not None else None
