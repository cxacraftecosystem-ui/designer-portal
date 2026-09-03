"""The layering law: AI output is a new row with its provenance, inert until a person accepts it.

Step 2 of ``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §5, and the whole of its §3.

**THERE IS NO GENERATION IN THIS MODULE, AND ITS ABSENCE IS THE POINT.** Nothing here calls a
provider, loads a model or produces a word of text. ``services/ai.py`` is not imported and neither
is anything that reaches it. What this module does is describe, validate and record what a model
produced — so that when the writing arrives it has nowhere to put an unattributed answer. The plan
says why the order is this way round: steps 1–3 "put provenance in before there is a backlog of
unattributed AI text", and that backlog already exists one row deep (see UNRECORDED below).
Generation is step 7 (extractive verbs, gated on measured memory) and step 8 (generative verbs,
behind acceptance). Neither is here.

THE FIVE RULES, and where each is actually enforced, because "written down" is not enforcement:

1. **Every layer is a ROW, never an edit.** :func:`layer_create_plan` is the only way to make one
   and it only ever produces a CREATE; there is no function in this module that writes content onto
   an existing layer, and none that writes anything at all onto the source it came from.
2. **Every layer carries provenance.** ``provider`` and ``model_id`` are required arguments with no
   defaults, and blank is refused: a caller with nothing to say must say :data:`UNRECORDED` in that
   word. The plan's reason is not obvious on the day the row is written — without the model id, a
   systematic error found in six months (a provider silently swapping a checkpoint, a quantization
   that mangles Odia numerals) cannot be traced to the material it damaged, and the only remedy left
   is to distrust the whole archive.
3. **A layer is inert until a person accepts it.** :func:`layer_create_plan` never sets an
   acceptance column; :func:`acceptance_plan` demands an actor id and refuses an empty one, because
   an acceptance nobody signed is not an acceptance.
4. **The report prints the accepted layer and names it as such.** Rendering belongs to step 3 and to
   another module; what is provided here is :func:`accepted_layers`, so that lane never has to
   decide for itself what "accepted" means.
5. **Deleting a derived layer never touches its source.** :func:`deletion_plan` returns exactly one
   plan, naming exactly one row id, setting exactly two columns — and ``tests/test_ai_layers.py``
   asserts all three, so a later change that widens it fails rather than quietly deleting evidence.

**THE PROPERTY AI BREAKS, WHICH IS WHY LAYERS LIVE IN THEIR OWN TABLE.** This project's core
invariant is that the handset and the server agree: the compensated sums, the ``DwPy`` helpers, the
hydration mirror, the on-device report that must render the same document as the server's. AI output
cannot have that property. The same audio through Tier 1 on a phone and Tier 3 in the cloud produces
different text, legitimately and for ever — and a cross-surface parity test that ever compared them
would be failing on the design rather than on a bug. So:

    NO AI-PRODUCED VALUE MAY FEED A FIELD THAT IS COMPARED ACROSS SURFACES, OR ANY DERIVED OR
    COMPUTED FIELD. AI layers are annexure content and suggestions.

Made true by construction rather than by this paragraph: every write this module can express is a
:class:`LayerWritePlan`, a plan can only name a table in :data:`WRITABLE_TABLES`, and
``DwStageEntry`` is not in it — constructing a plan that names it raises. That is the door the guard
holds shut, and ``test_ai_layers.py`` pushes on it in both directions (the refusal fires; the
executor's dispatch table has no entry for the stage table either).

**A SIXTH RULE THE PLAN DID NOT HAVE TO STATE, BECAUSE IT IS OLDER THAN THIS TABLE: A COPY OF A
TRANSCRIPT IS STILL THE TRANSCRIPT.** A layer's ``text`` is a copy of something derived from a
``MediaFile``, and who may read the CONTENT of a media file is decided per file by
``owned_or_granted_where(user, owner_field="uploadedById")`` — not by who may open the workshop. The
two sets differ: a ``DesignWorkshopViewer`` grant carries read and stage writes and says nothing
about media, which is why ``load_transcript_items`` takes a ``viewer`` and why its docstring calls
the ungated version a leak. Keeping a copy in a new table does not create a new right to read it. So
:func:`media_root` resolves the recording each layer stands on, :func:`layer_payload` takes
``text_withheld``, and the call sites apply the existing predicate rather than inventing one. The
provenance is served either way — it is not anybody's recording, and it is what the reviewer came
for.

**A NAME COLLISION WORTH KNOWING ABOUT BEFORE IT BITES.** ``stage_schema.Tier`` already exists and
means something completely different — BASIC / STANDARD / ADVANCED, how important a FIELD is to a
complete record. The tier here is which MACHINE ran the model (1 on the handset, 2 a small model on
the handset, 3 the cloud). They are unrelated, they will appear in the same report code, and so this
one is called :class:`AiTier` everywhere including in imports. Do not rename it to ``Tier``.
"""

from __future__ import annotations

import logging
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Any

from app.core.db import db

logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------------------
# The vocabulary
# --------------------------------------------------------------------------------------


class LayerKind(str, Enum):
    """Which rung of which chain a layer occupies. Mirrors the ``DwAiLayerKind`` Postgres enum.

    The first seven are the plan's two chains and the two extractive verbs §2.1 says to start with::

        audio ──▶ RAW_TRANSCRIPT ──▶ CLEANED_TRANSCRIPT ──▶ SUMMARY
        photo ──▶ OCR_TEXT       ──▶ STRUCTURED_TEXT
        (either text rung) ──▶ TAGS, METADATA

    TAGS and METADATA lead because a wrong tag is a suggestion a designer declines, whereas a wrong
    summary is model prose standing where an artisan's words were — the plan's "Recommended first
    Tier 2 verb: none of the generative ones". The generative kinds were declared before anything
    produced them, because the kind is what the annexure prints as a layer's NAME and step 3 needed
    the names before step 8 produced the text.

    **THE SECOND FIVE ARE THE VERBS THE USER ASKED FOR**, and each is a separate value rather than a
    reuse of a neighbouring one because each is a different PROMISE to whoever reads the document::

        (any prose rung, or supplied words) ──▶ PROOFREAD
        (supplied words only)               ──▶ EXPANDED
        (any prose rung, or supplied words) ──▶ TRANSLATION   ← a SIBLING, never a replacement
        photo / video                       ──▶ CAPTION
        audio / video                       ──▶ SUBTITLES     ← the only timed kind

    * :attr:`PROOFREAD` corrects spelling, grammar and punctuation and changes NOTHING else. It is
      deliberately not :attr:`CLEANED_TRANSCRIPT`, which restructures a conversation into speaker
      turns and — on this deployment's default ``REFINED_TRANSLATED`` — translates it into English.
      One heading for both would let a rewrite be printed under the word "proofread", which is a
      claim about faithfulness that the cleaned rung cannot make.
    * :attr:`EXPANDED` writes a designer's terse field note out into prose. **The only kind in this
      vocabulary that invents sentences** rather than transforming ones somebody said, which is why
      it may sit above nothing but words the caller supplied (see :data:`TEXT_ROOTED_KINDS`), why
      the annexure carries a warning naming it, and why nothing may derive from it.
    * :attr:`TRANSLATION` records ``sourceLanguage`` AND ``targetLanguage`` and stands BESIDE its
      original, which stays exactly where it was. The failure that shape is written against is
      already in this database: the media queue's default mode overwrites ``transcriptText`` with an
      English rewrite, in the column where a raw transcript is expected.
    * :attr:`CAPTION` is one sentence describing a photograph or a video — for the media annexure,
      and for a screen reader, which is the accessibility half of the same verb.
    * :attr:`SUBTITLES` is timed text: a cue list in ``payload``, rendered as SRT or WebVTT by
      ``services/subtitles``. The only kind whose principal content is structure, because a subtitle
      without its timings is a transcript and the timings are the entire verb.
    """

    RAW_TRANSCRIPT = "RAW_TRANSCRIPT"
    CLEANED_TRANSCRIPT = "CLEANED_TRANSCRIPT"
    SUMMARY = "SUMMARY"
    OCR_TEXT = "OCR_TEXT"
    STRUCTURED_TEXT = "STRUCTURED_TEXT"
    TAGS = "TAGS"
    METADATA = "METADATA"
    PROOFREAD = "PROOFREAD"
    EXPANDED = "EXPANDED"
    TRANSLATION = "TRANSLATION"
    CAPTION = "CAPTION"
    SUBTITLES = "SUBTITLES"


class AiTier(str, Enum):
    """Which machine produced it. Mirrors the ``DwAiTier`` Postgres enum.

    Not a ranking. Tier 1 is the only tier that works in a courtyard with no signal, and Tier 3 is
    the only one with the craft keyterm list; "higher is better" is false in both directions, which
    is why this is an enum of names rather than an integer somebody would inevitably compare.
    """

    TIER_1 = "TIER_1"
    TIER_2 = "TIER_2"
    TIER_3 = "TIER_3"

    @property
    def number(self) -> int:
        """1, 2 or 3 — for prose only ("Tier 3, in the cloud"), never for a comparison."""
        return int(self.value.rsplit("_", 1)[1])


class SourceKind(str, Enum):
    """The three things a layer can derive from, and there is no fourth.

    **THE THIRD ARRIVED WITH THE VERBS AND IS NOT LIKE THE OTHER TWO**, so it is worth being exact
    about before anybody reaches for it. ``MEDIA`` and ``LAYER`` are POINTERS: the evidence is a row
    that still exists and a reader can open it. ``SUPPLIED_TEXT`` is a COPY: the evidence is the
    words themselves, carried on the layer, because there is no row to point at.

    Why there is no row to point at, since this is the question every reader asks first: the verb
    that needs it — proofreading or expanding a designer's field note — runs on text being typed
    into a form that has NOT been saved, which is the moment the verb exists for. And even after the
    save there is deliberately nowhere on ``DwAiLayer`` to record which field it came from;
    ``test_the_layer_table_cannot_name_a_place_in_stage_data`` forbids ``fieldKey`` and its four
    siblings precisely so no later feature can answer "which box does this belong in" by writing
    model prose into that box.

    A copy rather than a pointer is also what ``REFERENCE_HYDRATION`` already does for a referenced
    record's display fields, for the reason that applies here unchanged: a field edited next March
    must not silently change what an annexure printed last year under somebody's name.
    """

    #: A ``MediaFile``: the raw rung of a chain — the transcript of a recording, the OCR or caption
    #: of a photograph, the subtitles of a video.
    MEDIA = "MEDIA"
    #: Another ``DwAiLayer``: every rung derived from a rung.
    LAYER = "LAYER"
    #: Words the caller supplied, kept verbatim on the row as the evidence the output is checked
    #: against. See the class docstring, and :data:`MAX_SOURCE_TEXT_CHARS` for the bound.
    SUPPLIED_TEXT = "SUPPLIED_TEXT"


class Decision(str, Enum):
    """What a person did to a layer. Mirrors the ``DwAiDecision`` Postgres enum."""

    ACCEPTED = "ACCEPTED"
    WITHDRAWN = "WITHDRAWN"


#: What ``provider`` / ``model_id`` hold when nobody recorded them, spelled out in that word.
#:
#: THIS IS NOT A HYPOTHETICAL, IT IS THE COMMON CASE ON DAY ONE. Every transcript this system has
#: ever produced was written by ``media_queue._transcript_write``, which stores ``transcriptText``,
#: ``transcriptSummary``, ``transcriptStatus`` and ``transcriptError`` — and not which of the four
#: providers in ``services/ai.py`` produced them. That module knows at call time (OpenAI, ElevenLabs
#: Scribe v2, Deepgram Nova-3 with ``language=multi``, Gemini, in the operator's configured order,
#: skipping any without a key and falling through on a failure) and nothing persists the answer.
#:
#: So a Tier 3 raw transcript registered from an existing ``MediaFile`` truthfully carries
#: ``UNRECORDED``, and the annexure can print "model not recorded" instead of naming a provider that
#: may not have produced it. This is the honest-unknown discipline the language-pack screen already
#: follows (``android/.../data/DwLanguagePacks.kt``): a fact nobody wrote down is stated as unknown,
#: never guessed, and never left as a null that reads like "none".
UNRECORDED = "UNRECORDED"

#: The tables this module may write to, and the whole list. See the module docstring: the guarantee
#: that AI output cannot reach a cross-surface or derived field is that ``DwStageEntry`` is absent
#: from this set and :class:`LayerWritePlan` refuses to name a table that is not in it.
WRITABLE_TABLES: frozenset[str] = frozenset({"DwAiLayer", "DwAiLayerDecision"})

#: Named so the refusal can name it, and so a reader grepping for the stage table finds this note.
#: A layer must never become a stage-entry value: stage data is what the designer wrote, it is
#: compared across surfaces, and derived fields are computed from it.
STAGE_TABLE = "DwStageEntry"

#: How much of a layer's text the list endpoint carries per row.
#:
#: A WORKSHOP CAN HOLD TWENTY-FIVE INTERVIEWS. An hour of speech is tens of kilobytes of transcript,
#: so a list that returned every layer's full text would be megabytes on a connection with one bar
#: of signal, re-sent every time the screen opened — and unread, because a list shows titles. The
#: full text is served only when a client asks for it (``includeText``), which is what an acceptance
#: screen does for the one layer a person is about to put their name to.
#:
#: 280 characters is roughly two lines on a handset, enough to recognise a layer by its opening
#: sentence. It is NOT the same rule as ``report_annexures.first_line``, which the first draft of
#: this note claimed it was: that one elides at 160 characters and strips the speaker label off the
#: front, because it titles a RECORDING in a picker and a column of rows all reading "Interviewer:"
#: cannot be chosen from. This one is titling a LAYER whose sibling rows are the same recording at
#: different rungs, so the label is worth its width — the difference between a raw transcript and a
#: cleaned one is often visible in exactly those first few words.
PREVIEW_CHARS = 280


# --------------------------------------------------------------------------------------
# What may sit above what
# --------------------------------------------------------------------------------------

#: Kinds whose principal content is prose in ``text``.
TEXT_KINDS: frozenset[LayerKind] = frozenset(
    {
        LayerKind.RAW_TRANSCRIPT,
        LayerKind.CLEANED_TRANSCRIPT,
        LayerKind.SUMMARY,
        LayerKind.OCR_TEXT,
        LayerKind.PROOFREAD,
        LayerKind.EXPANDED,
        LayerKind.TRANSLATION,
        LayerKind.CAPTION,
    }
)

#: Kinds whose principal content is structure in ``payload``. ``STRUCTURED_TEXT`` is here rather
#: than above because the thing that makes it "structured" IS the structure: the identity-card read
#: in ``services/identity_ocr.py`` produces named candidate fields, not a paragraph, and storing
#: those as prose would mean re-parsing them to use them.
#:
#: ``SUBTITLES`` is here for the same reason stated one degree more sharply: the cue list IS the
#: verb. A subtitle file whose timings were kept as prose would have to be re-parsed to be rendered,
#: and a renderer that parses its own output is a renderer that will one day disagree with itself
#: about where a line begins. It carries ``text`` as well — a plain reading of the same cues — so the
#: annexure has something to print and a search has something to match; see :func:`_check_content`
#: for why the secondary slot is permitted rather than forbidden.
STRUCTURED_KINDS: frozenset[LayerKind] = frozenset(
    {
        LayerKind.STRUCTURED_TEXT,
        LayerKind.TAGS,
        LayerKind.METADATA,
        LayerKind.SUBTITLES,
    }
)

#: Kinds that derive directly from a ``MediaFile`` — the bottom rung of each chain, and the only
#: rungs whose evidence is not itself a layer.
#:
#: **THE ONE MEDIA-ROOTED READING THIS REPOSITORY PRODUCES THAT IS NOT HERE, AND WHY IT IS NOT.**
#: ``POST /media/analyze-measurement`` sends a photograph of a craft object on a grid sheet to Gemini
#: and gets back a number of inches, which lands in ``ProductDocumentation.lengthInches``. That is a
#: model reading a ``MediaFile`` and producing a value a person ought to accept — the exact shape this
#: table exists for — and it CANNOT be registered here today. Three reasons, in ascending order of how
#: hard they are to fix, because the third is the one that makes this a migration and not an omission:
#:
#: 1. ``DwAiLayerKind`` has no value that means "a number read off a photograph", and it is a real
#:    Postgres enum: adding one is a migration, and a value Python can produce while Postgres refuses
#:    it is a 500 on the write path. ``test_the_python_vocabulary_and_the_postgres_enum_agree`` in the
#:    dictation lane pins that pairing for its own enum for the same reason.
#: 2. Widening this set is a deliberate act with a reason, exactly as ``ALLOWED_PARENTS``' narrowing
#:    below is. A ``MEASUREMENT`` kind would be the first media-rooted kind whose content is neither
#:    prose nor a transcript, so the note under ``ALLOWED_PARENTS`` — a model that concludes something
#:    about a photograph without producing text first "leaves an annexure printing conclusions with
#:    nothing underneath them to check" — has to be answered for it rather than waved past. The
#:    photograph itself is the evidence rung a reader can check, which is an answer, but it is a
#:    different answer from the one this table currently gives and it belongs in the diff that makes it.
#: 3. **``DwAiLayer.designWorkshopId`` is NOT NULL and points at ``DesignWorkshop``.** A grid
#:    measurement's destination is ``ProductDocumentation`` / ``ToolDocumentation``, scoped by
#:    ``workshopId`` to ``Workshop`` — a different model. There is no ``DesignWorkshop`` to hang the row
#:    on. And by ``test_the_layer_table_cannot_name_a_place_in_stage_data`` there is deliberately no
#:    column that could record WHICH field a reading filled, so even with a kind and a scope the row
#:    could not say what it was a measurement OF.
#:
#: Until that migration exists, the provenance of a grid measurement travels with the suggestion and is
#: stored beside the value — see ``services/measurement_provenance``, which deliberately uses the
#: vocabulary such a layer would carry so that the migration is an addition rather than a translation.
#: Nothing in this module reaches that path and nothing in that path reaches this one.
#:
#: **TWO KINDS WERE ADDED TO THIS SET BY THE VERBS, AND THE NOTE UNDER ``ALLOWED_PARENTS`` HAD TO BE
#: ANSWERED FOR BOTH RATHER THAN WAVED PAST.** That note says a model which concludes something about
#: a photograph without producing text first "leaves an annexure printing conclusions with nothing
#: underneath them to check". The answer for these two is that the evidence rung is not missing — it
#: is the media file itself, which both annexures already print:
#:
#: * ``CAPTION`` describes a photograph, and the photograph is what a reader checks the sentence
#:   against. Requiring an OCR rung first would be worse than useless: most workshop photographs
#:   carry no text at all, so the intermediate rung would be empty and the caption would have to
#:   stand on an empty row instead of on the picture.
#: * ``SUBTITLES`` cannot be derived from text AT ALL, which is the sharper case. Timings exist only
#:   in the provider's answer about the audio; a subtitle rung standing on a ``RAW_TRANSCRIPT`` would
#:   have to invent when each line was spoken, and an invented timestamp is a fabricated fact of
#:   exactly the kind rule 2 exists to prevent. So it is media-rooted or it does not exist.
MEDIA_ROOTED_KINDS: frozenset[LayerKind] = frozenset(
    {
        LayerKind.RAW_TRANSCRIPT,
        LayerKind.OCR_TEXT,
        LayerKind.CAPTION,
        LayerKind.SUBTITLES,
    }
)

#: Kinds that may stand on words the caller supplied — see :attr:`SourceKind.SUPPLIED_TEXT`.
#:
#: **``EXPANDED`` IS IN HERE AND IN NOTHING ELSE, WHICH IS THE NARROWEST RULE IN THIS MODULE AND THE
#: MOST DELIBERATE.** Expanding is the one verb that writes sentences nobody said. Run over a
#: designer's own shorthand it turns their note into their prose, and they are standing there to
#: judge it. Run over a ``RAW_TRANSCRIPT`` it would put invented words in an artisan's mouth, in a
#: document that names that artisan, and no acceptance screen can make that safe — the person
#: accepting it is not the person being quoted. So the verb is unavailable over anybody else's words,
#: by construction rather than by policy, and a caller that tries is told why.
#:
#: ``PROOFREAD`` and ``TRANSLATION`` are here as well as in :data:`ALLOWED_PARENTS`, because both are
#: wanted in both places: a designer proofreads a note they are typing AND a transcript they are
#: about to submit, and both are transformations of words that already exist rather than inventions.
TEXT_ROOTED_KINDS: frozenset[LayerKind] = frozenset(
    {
        LayerKind.PROOFREAD,
        LayerKind.EXPANDED,
        LayerKind.TRANSLATION,
    }
)

#: How many characters of supplied text a layer may carry as its source.
#:
#: A BOUND ON THE EVIDENCE, not on the verb. The column is unbounded TEXT and the reason to bound it
#: here is that this string is stored on the row, returned on every listing of that row, and printed
#: in the annexure as "made from" — so an unbounded one is paid for by every later reader rather than
#: by the writer, which is the argument ``MAX_NOTES_CHARS`` and ``MAX_EXPORT_WARNING_CHARS`` already
#: make one schema over.
#:
#: 20,000 characters is roughly ten typed pages: far more than the terse field note these verbs exist
#: for, and comfortably under the chat model's own ceiling (``ai._REFINE_MAX_CHARS`` clips at 48,000),
#: so a body inside this bound is never silently truncated by the provider call underneath it. A body
#: over it is REFUSED rather than clipped, because a proofread of the first ten pages of a twelve-page
#: note, presented as a proofread of the note, is a layer whose source text is not what it says.
MAX_SOURCE_TEXT_CHARS = 20_000

#: kind -> the parent kinds it may sit above. **NO LONGER "every kind not in
#: :data:`MEDIA_ROOTED_KINDS`", which is what this note said while there were only two sets.** With
#: :data:`TEXT_ROOTED_KINDS` there are three places a kind can stand and a kind may be in more than
#: one of them: ``PROOFREAD`` and ``TRANSLATION`` are here AND text-rooted, and ``EXPANDED`` is
#: text-rooted and deliberately absent from here. A kind in none of the three cannot be created at
#: all, which is what ``test_every_kind_can_be_created_somehow`` checks.
#:
#: TWO PLACES THIS IS WIDER THAN THE PLAN'S ARROWS, both deliberate:
#:
#: * ``SUMMARY`` may sit on a RAW transcript as well as on a cleaned one. The cleanup rung is
#:   optional — Tier 2 may not have a cleanup verb at all when summarisation arrives — and forcing a
#:   cleaned row to exist first would mean inventing an intermediate layer nobody asked for and that
#:   no model produced, which is a fabricated provenance record.
#: * ``TAGS`` and ``METADATA`` may sit on any text rung, since an extractive verb reads text and
#:   there is more than one kind of text to read.
#:
#: AND ONE PLACE IT IS NARROWER, also deliberate: neither extractive verb may sit directly on a
#: ``MediaFile``. A model that tags a photograph without producing text first leaves an annexure
#: printing conclusions with nothing underneath them to check — the reader cannot see WHAT was
#: tagged. If a Tier 3 verb that genuinely reads audio directly is ever wanted, widen this table
#: deliberately and say in the annexure that the evidence rung is absent; do not let it in by
#: accident.
#: Prose kinds that are a transformation of words somebody actually said or wrote — every text kind
#: except :attr:`LayerKind.EXPANDED`.
#:
#: **NOTHING MAY BE DERIVED FROM AN EXPANSION, AND THIS SET IS HOW THAT IS ENFORCED.** An expansion
#: is invented prose. A summary of one summarises an invention; a translation of one carries the
#: invention into a second language where the reader has even less chance of noticing; a tag
#: extracted from one puts a machine's guess into the craft vocabulary that biases every future
#: transcription. Each of those is a chain in which the fabricated rung gets further from the person
#: who could have caught it, and by the third rung the annexure's "made from" line names another
#: layer rather than anything a human said. So an expansion is a leaf: it is accepted or declined on
#: its own, against the note it was made from, by the designer who wrote the note.
DERIVABLE_PROSE_KINDS: frozenset[LayerKind] = frozenset(TEXT_KINDS - {LayerKind.EXPANDED})

ALLOWED_PARENTS: Mapping[LayerKind, frozenset[LayerKind]] = {
    LayerKind.CLEANED_TRANSCRIPT: frozenset({LayerKind.RAW_TRANSCRIPT}),
    LayerKind.SUMMARY: frozenset({LayerKind.RAW_TRANSCRIPT, LayerKind.CLEANED_TRANSCRIPT}),
    LayerKind.STRUCTURED_TEXT: frozenset({LayerKind.OCR_TEXT}),
    LayerKind.TAGS: frozenset(DERIVABLE_PROSE_KINDS | {LayerKind.STRUCTURED_TEXT}),
    LayerKind.METADATA: frozenset(DERIVABLE_PROSE_KINDS | {LayerKind.STRUCTURED_TEXT}),
    # A PROOFREAD OF A PROOFREAD IS REFUSED, and not for tidiness. The second run has no way to tell
    # a correction it is making from one the first run already made, so the chain accumulates
    # "corrections" nobody asked for while each row's provenance line truthfully says it only fixed
    # spelling — which is how a passage drifts away from what was said, one defensible step at a
    # time, with every step individually accepted. Proofread the original again if the first attempt
    # was wrong; the first row stays as the record that it was.
    LayerKind.PROOFREAD: frozenset(DERIVABLE_PROSE_KINDS - {LayerKind.PROOFREAD}),
    # A TRANSLATION OF A TRANSLATION IS REFUSED for a different and harder reason: pivot translation
    # compounds error invisibly, and the provenance line would print "made from" a row whose own
    # language is not the one the artisan spoke. A reader who wanted to check the Odia would be
    # checking it against an English paraphrase. Translate from the original — which is exactly why
    # this verb produces a SIBLING and leaves the original in place.
    LayerKind.TRANSLATION: frozenset(DERIVABLE_PROSE_KINDS - {LayerKind.TRANSLATION}),
}


# --------------------------------------------------------------------------------------
# Refusals
# --------------------------------------------------------------------------------------


class LayerRuleViolation(ValueError):
    """A write that would break the layering law, refused with a sentence naming the next move.

    A ``ValueError`` rather than an ``HTTPException``, so this module stays importable — and
    testable — with no framework underneath it, exactly as ``questionnaire_forms.QuestionnaireEditError``
    and ``address.DistrictReconciliationError`` are. The route translates it to a status code; the
    sentence it carries is written for the designer who will read it, not for the developer who
    caused it, because this repository's errors are sentences that name what to do and never codes.
    """


def _require_text(value: str | None, *, what: str, remedy: str) -> str:
    text = (value or "").strip()
    if not text:
        raise LayerRuleViolation(f"{what} {remedy}")
    return text


# --------------------------------------------------------------------------------------
# The source: a discriminated pair, not two optional pointers
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class LayerSource:
    """Where a layer came from: a ``MediaFile``, another layer, or words the caller supplied — one.

    MODELLED AS A DISCRIMINATED VALUE RATHER THAN AS THREE NULLABLE COLUMNS because the
    three-nullable-columns shape is the one that rots: every reader has to re-derive "which of these
    is set", every writer can forget to clear the others, and the invalid states are representable in
    between. Here they are not — the only ways to build one are :meth:`media`, :meth:`layer` and
    :meth:`supplied_text`, and none of them can produce a source that names two things or nothing.

    The database holds the same rule in the shape it can express: three nullable columns plus a CHECK
    that exactly one is non-null (``DwAiLayer_source_is_exactly_one``, widened from two by migration
    20260812150000). Both guards exist because they answer different questions — the constraint
    covers rows this API never sees, such as a backfill run by hand, and this one produces a sentence
    a client can act on.
    """

    kind: SourceKind
    #: The row's id for :attr:`SourceKind.MEDIA` and :attr:`SourceKind.LAYER`; empty for
    #: :attr:`SourceKind.SUPPLIED_TEXT`, which names no row and carries :attr:`text` instead.
    id: str
    #: The parent layer's kind, when it is known.
    #:
    #: KNOWN AT CREATE TIME AND NOT AT READ TIME, which is why this is optional here and demanded in
    #: :func:`_check_chain`. Creating a layer means holding the parent row, so its kind is to hand
    #: and the chain rule can be applied. Reading a stored row back gives only ``sourceLayerId`` —
    #: there is no ``sourceLayerKind`` column and there deliberately is not one, because a copy of
    #: the parent's kind is a second place for it to be wrong. Defaulting it on the read side would
    #: be inventing a fact about a row nobody has loaded, which is the failure this repository names
    #: honest-unknown; the read side simply does not need it.
    layer_kind: LayerKind | None = None
    #: The words themselves, for :attr:`SourceKind.SUPPLIED_TEXT` and for nothing else.
    #:
    #: THE EVIDENCE, NOT A POINTER TO IT. See :class:`SourceKind`: there is no row to point at, and
    #: there is deliberately no column on this table that could name a place in stage data. Bounded
    #: by :data:`MAX_SOURCE_TEXT_CHARS`, because this string is read back on every listing of the row
    #: and printed in the annexure under "made from".
    text: str | None = None

    def __post_init__(self) -> None:
        if self.kind is SourceKind.SUPPLIED_TEXT:
            supplied = (self.text or "").strip()
            if not supplied:
                raise LayerRuleViolation(
                    "A layer produced from supplied words must carry the words it was given. Send "
                    "the text to work on — an annexure that prints a correction has to be able to "
                    "show what was corrected."
                )
            # THE LENGTH IS NOT CHECKED HERE, DELIBERATELY, AND THE OMISSION IS THE POINT OF THIS
            # NOTE. `source_of` builds one of these out of a STORED row, so a size rule enforced in
            # this constructor would make an over-long row unreadable rather than uncreatable — the
            # list would drop its source, the annexure would lose its "made from" line, and lowering
            # the bound one day would silently blind the reader to every row written under the old
            # one. The bound is a create-time policy and lives on the create path; see
            # :func:`_check_source`.
            if self.id:
                raise LayerRuleViolation(
                    "A layer produced from supplied words names no row. Send either the words or "
                    "the id of the layer to work on, never both."
                )
            if self.layer_kind is not None:
                raise LayerRuleViolation(
                    "Supplied words have no parent layer kind. Drop it, or derive this layer from "
                    "the stored layer instead of from the text."
                )
            return
        if not (self.id or "").strip():
            raise LayerRuleViolation(
                "A layer must say what it was derived from. Send the id of the recording or of the "
                "layer it was produced from."
            )
        if self.text is not None:
            raise LayerRuleViolation(
                "A layer derived from a stored row carries no supplied text. Drop it — the words "
                "are read from the row, so a second copy on the layer is a second place for them "
                "to disagree."
            )
        if self.kind is SourceKind.MEDIA and self.layer_kind is not None:
            raise LayerRuleViolation(
                "A layer derived from a recording has no parent layer kind. Drop it, or derive this "
                "layer from the transcript instead of from the file."
            )

    @classmethod
    def media(cls, media_id: str) -> LayerSource:
        """The raw rung: derived from a recording or a photograph."""
        return cls(kind=SourceKind.MEDIA, id=media_id)

    @classmethod
    def layer(cls, layer_id: str, layer_kind: LayerKind | None = None) -> LayerSource:
        """Every rung above the raw one: derived from another layer.

        ``layer_kind`` is the parent's kind and is required to CREATE — pass it from the loaded
        parent row. It is omitted when reading a stored row back, where it is not known and not
        needed; see the field's note.
        """
        return cls(kind=SourceKind.LAYER, id=layer_id, layer_kind=layer_kind)

    @classmethod
    def supplied_text(cls, text: str) -> LayerSource:
        """Words the caller gave the verb — a designer's field note, typically not yet saved."""
        return cls(kind=SourceKind.SUPPLIED_TEXT, id="", text=text)

    @property
    def columns(self) -> dict[str, str | None]:
        """The source as the three nullable columns, with the two unused ones explicitly null."""
        if self.kind is SourceKind.MEDIA:
            return {"sourceMediaId": self.id, "sourceLayerId": None, "sourceText": None}
        if self.kind is SourceKind.LAYER:
            return {"sourceMediaId": None, "sourceLayerId": self.id, "sourceText": None}
        return {
            "sourceMediaId": None,
            "sourceLayerId": None,
            # Stripped on the way in so the stored evidence is the words and not the whitespace a
            # textarea added around them — and so a body of nothing but spaces cannot pass the
            # emptiness check above and then be stored as a source that prints as blank.
            "sourceText": (self.text or "").strip(),
        }


def source_of(row: Any) -> LayerSource:
    """Read a stored row's source back as a pair, refusing the two shapes the CHECK forbids.

    Defensive on purpose: a row that reached the table around this module — the hand-run backfill
    the constraint exists for, or a database restored from before the constraint — must fail loudly
    here rather than be quietly treated as media-sourced by a ``getattr`` that found a null.
    """
    media_id = (getattr(row, "sourceMediaId", None) or "").strip()
    layer_id = (getattr(row, "sourceLayerId", None) or "").strip()
    supplied = (getattr(row, "sourceText", None) or "").strip()
    named = [bool(media_id), bool(layer_id), bool(supplied)]
    if sum(named) != 1:
        raise LayerRuleViolation(
            f"Layer {getattr(row, 'id', '?')} names "
            f"{'more than one source' if any(named) else 'no source'}, which cannot be printed or "
            "trusted. A layer derives from exactly one recording, exactly one other layer, or "
            "exactly one passage of supplied words — repair the row before using it."
        )
    if media_id:
        return LayerSource.media(media_id)
    if supplied:
        return LayerSource.supplied_text(supplied)
    # No parent kind: it is not stored on this row and is not guessed here. See LayerSource.layer_kind.
    return LayerSource.layer(layer_id)


#: How far up a chain the recording at its foot is looked for before the walk gives up.
#:
#: The deepest chain this vocabulary allows is four rungs (audio → raw → cleaned → summary → tags),
#: so eight is twice what any legitimate row needs. It is a bound and not a guess about depth: the
#: FOREIGN KEY on ``sourceLayerId`` points at this same table, and Postgres is perfectly happy with a
#: row whose ``sourceLayerId`` is its own id or with two rows naming each other. Nothing in this API
#: can write one, but a hand-run backfill can, and an unbounded walk over one would spin inside a
#: request a designer is waiting on. Visited ids are tracked as well, so a cycle stops at once rather
#: than after eight hops.
MAX_CHAIN_HOPS = 8


def media_root(row: Any, by_id: Mapping[str, Any]) -> str | None:
    """The recording at the foot of this layer's chain, or None when it cannot be reached.

    **THIS IS THE FUNCTION AN ENTITLEMENT CHECK STANDS ON, WHICH IS WHY IT FAILS CLOSED.** A layer's
    content is a copy of something derived from a MediaFile, and who may read a MediaFile is decided
    per file by ``owned_or_granted_where(user, owner_field="uploadedById")`` — a different question
    from who may open the workshop, and deliberately so (``design_workshop_viewers`` grants READ and
    stage writes and nothing about media). Every answer of None therefore means "withhold", never
    "allow": an unresolvable chain is exactly the shape a hand-written row would have, and a leak is
    not the failure to choose when the evidence runs out.

    ``by_id`` must hold every layer of the workshop INCLUDING the deleted ones. A live layer whose
    parent is deleted cannot be produced through this API — ``deletion_plan`` refuses to delete a
    layer that live layers stand on — but reading a narrowed list and walking within it would break
    the chain for a perfectly ordinary row, and fail-closed then means withholding text somebody is
    entitled to.
    """
    current = row
    seen: set[str] = set()
    for _ in range(MAX_CHAIN_HOPS):
        try:
            source = source_of(current)
        except LayerRuleViolation:
            return None
        if source.kind is SourceKind.MEDIA:
            return source.id
        if source.id in seen:
            return None
        seen.add(source.id)
        parent = by_id.get(source.id)
        if parent is None:
            return None
        current = parent
    return None


def media_roots(rows: Sequence[Any]) -> dict[str, str | None]:
    """``{layer id: the recording at the foot of its chain}``, None where the walk did not get there.

    Computed once for a whole workshop so that the list read asks the media table one question
    instead of one per layer.

    **NOT THE FUNCTION TO GATE ON ANY MORE — USE :func:`chain_roots`.** None here means "no recording
    was reached", which used to have exactly one cause (the chain is broken, so withhold) and now has
    two: a layer rooted in words the caller supplied has no recording and never had one, and
    withholding its text would hide a designer's own note from the designer who wrote it. This
    function still answers the narrower question and is still what the media query is built from,
    because a supplied-text root contributes no media id to look up.
    """
    by_id = {str(getattr(row, "id", "")): row for row in rows}
    return {str(getattr(row, "id", "")): media_root(row, by_id) for row in rows}


class RootKind(str, Enum):
    """What is at the foot of a layer's chain, once the walk has finished.

    THREE ANSWERS BECAUSE THE GATE NEEDS THREE, and collapsing any two of them leaks or hides.
    """

    #: A ``MediaFile``. Whether the text may be read is decided per file by the media gate.
    MEDIA = "MEDIA"
    #: Words the caller supplied. There is no recording to gate on — this is workshop content, and
    #: whoever may open the workshop may read it. See :attr:`SourceKind.SUPPLIED_TEXT` for the limit
    #: that follows from that, which is stated rather than hidden.
    SUPPLIED_TEXT = "SUPPLIED_TEXT"
    #: The walk did not finish: a broken pointer, a cycle, a row written around this API. **Withhold.**
    UNRESOLVED = "UNRESOLVED"


@dataclass(frozen=True, slots=True)
class ChainRoot:
    """The foot of one layer's chain: which kind of evidence, and the recording's id when there is one."""

    kind: RootKind
    media_id: str | None = None

    def withheld_from(self, readable_media_ids: Iterable[str]) -> bool:
        """Whether a caller who may read exactly ``readable_media_ids`` must not see this layer's text.

        **THE WHOLE GATE, IN ONE PLACE, SO THE TWO CALL SITES CANNOT DRIFT.** The list route and the
        single-layer check both ask this; before it existed each spelled the comparison itself, and
        the second one was the one that had to be got right on accept — where a wrong answer is
        somebody's name printed under a transcript they were never allowed to open.

        Fails closed on :attr:`RootKind.UNRESOLVED`, for :func:`media_root`'s stated reason: an
        unresolvable chain is exactly the shape a hand-written row has, and a leak is not the failure
        to choose when the evidence runs out.
        """
        if self.kind is RootKind.SUPPLIED_TEXT:
            return False
        if self.kind is RootKind.MEDIA and self.media_id:
            return self.media_id not in set(readable_media_ids)
        return True


def chain_root(row: Any, by_id: Mapping[str, Any]) -> ChainRoot:
    """Walk one layer down to the evidence it stands on. See :class:`ChainRoot`.

    The same walk :func:`media_root` performs, keeping the answer it discards: WHY there is no
    recording. That distinction is the difference between "this account may not read the artisan's
    voice" and "there is no artisan's voice here, only the designer's own words", and only one of
    those two is a reason to blank a screen.
    """
    current = row
    seen: set[str] = set()
    for _ in range(MAX_CHAIN_HOPS):
        try:
            source = source_of(current)
        except LayerRuleViolation:
            return ChainRoot(RootKind.UNRESOLVED)
        if source.kind is SourceKind.MEDIA:
            return ChainRoot(RootKind.MEDIA, source.id)
        if source.kind is SourceKind.SUPPLIED_TEXT:
            return ChainRoot(RootKind.SUPPLIED_TEXT)
        if source.id in seen:
            return ChainRoot(RootKind.UNRESOLVED)
        seen.add(source.id)
        parent = by_id.get(source.id)
        if parent is None:
            return ChainRoot(RootKind.UNRESOLVED)
        current = parent
    return ChainRoot(RootKind.UNRESOLVED)


def chain_roots(rows: Sequence[Any]) -> dict[str, ChainRoot]:
    """``{layer id: what its chain stands on}``, computed once for a whole workshop.

    ``rows`` must hold every layer of the workshop INCLUDING the deleted ones, for
    :func:`media_root`'s stated reason: a live layer whose parent was deleted cannot be produced
    through this API, but walking a narrowed list would break the chain for an ordinary row and
    fail-closed then means withholding text somebody is entitled to.
    """
    by_id = {str(getattr(row, "id", "")): row for row in rows}
    return {str(getattr(row, "id", "")): chain_root(row, by_id) for row in rows}


def media_ids_to_check(roots: Mapping[str, ChainRoot]) -> set[str]:
    """The recordings whose read permission has to be looked up for this set of layers.

    Only the media-rooted ones contribute: an unresolved chain is withheld without asking anybody,
    and a supplied-text chain has nothing to ask about. One query for a whole workshop rather than
    ONE PER LAYER — twenty-five interviews at two rungs each is FIFTY round trips, and fifty is the
    number that decides this whether a hop costs 756 ms (the cross-region link this was measured
    against, on which it was a screen that never finished opening) or the one to two milliseconds a
    co-located one costs since 2026-09-02 (``services/concurrency.py``). Fifty queries to answer one
    permission question is the wrong shape at any latency, and it grows with the workshop.
    """
    return {
        root.media_id for root in roots.values() if root.kind is RootKind.MEDIA and root.media_id
    }


def is_kind(row: Any, kind: LayerKind) -> bool:
    """Whether a stored row is of this kind, tolerating an enum member or a plain string.

    Deliberately a comparison and not a lookup: a row whose stored kind is outside the vocabulary
    answers False rather than raising, because this is used to NARROW a list and one unreadable row
    must not empty the screen.
    """
    return _enum_str(getattr(row, "kind", None)) == kind.value


def _kind_of(value: Any) -> LayerKind:
    """A kind from whatever Prisma handed back — an enum member or a plain string."""
    return LayerKind(str(getattr(value, "value", value)))


def _tier_of(value: Any) -> AiTier:
    return AiTier(str(getattr(value, "value", value)))


# --------------------------------------------------------------------------------------
# Write plans: the only way this module can change anything
# --------------------------------------------------------------------------------------


class Operation(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"


@dataclass(frozen=True, slots=True)
class LayerWritePlan:
    """One intended database write, described rather than performed.

    WHY A PLAN AND NOT A COROUTINE THAT WRITES. Two reasons, and the second is the load-bearing one.

    A plan can be asserted about by a test with no database, no event loop and no Prisma client — so
    the rules above are covered by ``pytest`` on a laptop rather than by a round-trip script somebody
    runs occasionally. This repository's own history says the untestable half is the half that is
    wrong.

    And a plan names its TABLE, which is what makes rule "no AI value in a compared or derived field"
    true by construction instead of by convention: ``__post_init__`` refuses any table outside
    :data:`WRITABLE_TABLES`, so there is no expressible write from this module into
    :data:`STAGE_TABLE`. A later change that tries to open that door has to delete this check, which
    is a visible act in a diff and a failing test rather than a quiet new call site.
    """

    table: str
    operation: Operation
    data: Mapping[str, Any]
    #: Present for an UPDATE only, and always exactly ``{"id": ...}`` — one row, named.
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in WRITABLE_TABLES:
            raise LayerRuleViolation(
                f"An AI layer may not be written into {self.table}. AI output is annexure content "
                f"and a suggestion: it never feeds a field that is compared between the handset and "
                f"the server, and never a derived or computed one, because the same audio through "
                f"Tier 1 on a phone and Tier 3 in the cloud differs legitimately and for ever. "
                f"Write it to one of {', '.join(sorted(WRITABLE_TABLES))}."
            )
        if self.operation is Operation.UPDATE and not self.where:
            raise LayerRuleViolation(
                "An update must name the single row it changes. Pass where={'id': layer_id}."
            )
        if self.operation is Operation.CREATE and self.where:
            raise LayerRuleViolation("A create names no existing row. Drop the where clause.")


# --------------------------------------------------------------------------------------
# Rule 1 and rule 2: creating a layer
# --------------------------------------------------------------------------------------


def layer_create_plan(
    *,
    workshop_id: str,
    kind: LayerKind,
    tier: AiTier,
    source: LayerSource,
    provider: str,
    model_id: str,
    model_version: str | None = None,
    language: str | None = None,
    source_language: str | None = None,
    target_language: str | None = None,
    produced_at: datetime | None = None,
    text: str | None = None,
    payload: Any = None,
    created_by_id: str | None = None,
) -> LayerWritePlan:
    """The one way a layer comes into existence: a new row, with its provenance, accepted by nobody.

    ``provider`` and ``model_id`` are positional-by-keyword and have NO DEFAULT, which is the point
    of rule 2. A caller that does not know must pass :data:`UNRECORDED` in that word — a deliberate
    keystroke, visible in a diff and in the row — rather than omitting an argument and getting a null
    that reads like "none". The blank string is refused with a sentence naming both options.

    ``produced_at`` may be None and is left as null when it is, rather than defaulting to now(). A
    transcript the queue produced last March and registered as a layer today would otherwise carry
    today's date as a statement about when the model ran, which is a fabricated fact of exactly the
    kind rule 2 exists to prevent. ``createdAt`` — the row's own default — always says when the row
    appeared, and the two are not the same question.

    No acceptance column is set here and there is no argument that could set one. Rule 3 is that a
    layer arrives inert; a create that could arrive accepted would make the acceptance meaningless
    for every row a future generation path writes.
    """
    workshop = _require_text(
        workshop_id,
        what="A layer belongs to a workshop.",
        remedy="Send the workshop id it was produced for.",
    )
    _check_chain(kind, source)
    _check_source(source)
    _check_content(kind, text=text, payload=payload)
    languages = _check_languages(
        kind, language=language, source_language=source_language, target_language=target_language
    )

    provenance_remedy = (
        f"Name the provider and the model that produced this layer, or pass '{UNRECORDED}' in that "
        f"word if the run did not record one — a systematic model error found in six months cannot "
        f"be traced to the material it damaged without it."
    )
    clean_provider = _require_text(
        provider, what="This layer does not say what produced it.", remedy=provenance_remedy
    )
    clean_model = _require_text(
        model_id, what="This layer does not say which model produced it.", remedy=provenance_remedy
    )

    data: dict[str, Any] = {
        "designWorkshopId": workshop,
        "kind": kind.value,
        "tier": tier.value,
        **source.columns,
        "provider": clean_provider,
        "modelId": clean_model,
        "modelVersion": (model_version or "").strip() or None,
        **languages,
        "producedAt": produced_at,
        "text": (text or "").strip() or None,
        "payload": payload,
        "createdById": created_by_id or None,
    }
    return LayerWritePlan(table="DwAiLayer", operation=Operation.CREATE, data=data)


def check_placement(kind: LayerKind, source: LayerSource) -> None:
    """Refuse a (kind, source) pair BEFORE anybody spends money proving it is refused.

    **THE SAME TWO CHECKS :func:`layer_create_plan` RUNS, EXPOSED SO THEY CAN BE RUN EARLIER, AND
    THE REASON IS NOT TIDINESS.** A generating caller — ``services/ai_verbs`` and its routes — reads
    the source, sends its words to a provider, and only then builds the plan. So a pair this module
    was always going to refuse was being refused AFTER the text had left the building and after the
    designer's daily allowance had paid for it. Two things went wrong in that order, and only one of
    them was visible:

    * A translation of an ``EXPANDED`` layer is forbidden precisely so an invention cannot get
      further from the person who could catch it (see :data:`DERIVABLE_PROSE_KINDS`). Refusing it at
      plan time still refuses the ROW — the rule held — but the invented prose had already been sent
      to a third party to be translated, which is the half of the rule that is about the words rather
      than about the table.
    * The designer was charged a run, and then handed a 422 for a request that could never have
      succeeded with any provider answer whatsoever.

    Nothing here is a new rule and nothing here can pass something :func:`layer_create_plan` would
    refuse: it calls the identical two functions, so the plan remains the authority and this is only
    the earliest point at which the same answer is available. A caller that skips it is not less
    safe, only more expensive.
    """
    _check_chain(kind, source)
    _check_source(source)


def _where_it_may_sit(kind: LayerKind) -> str:
    """Every place one kind is allowed to stand, in one clause a caller can act on.

    The refusals used to name only the branch that failed — "it may sit above: RAW_TRANSCRIPT,
    CLEANED_TRANSCRIPT" — which was complete while a kind belonged to one set and is not now that a
    kind can be legal over a stored layer AND over supplied words. A caller told half the answer
    tries the other half by guessing, and the guesses cost a round trip each.
    """
    places: list[str] = []
    if kind in MEDIA_ROOTED_KINDS:
        places.append("a recording or a photograph")
    parents = sorted(k.value for k in ALLOWED_PARENTS.get(kind, frozenset()))
    if parents:
        places.append(f"a layer of kind {', '.join(parents)}")
    if kind in TEXT_ROOTED_KINDS:
        places.append("words sent with the request")
    return "; ".join(places) or "nothing"


def _check_source(source: LayerSource) -> None:
    """The create-time policy on a source: how much supplied evidence one layer may carry.

    Separate from :meth:`LayerSource.__post_init__`, which holds the STRUCTURAL rules, because that
    constructor also runs over stored rows in :func:`source_of` — so a bound enforced there would
    make an over-long row unreadable instead of uncreatable, and lowering it later would blind the
    annexure to every row written under the old one. See the note in that method.
    """
    if source.kind is not SourceKind.SUPPLIED_TEXT:
        return
    supplied = (source.text or "").strip()
    if len(supplied) > MAX_SOURCE_TEXT_CHARS:
        raise LayerRuleViolation(
            f"That is {len(supplied):,} characters and at most {MAX_SOURCE_TEXT_CHARS:,} can be "
            f"worked on at once. Send the passage you want done rather than the whole stage — a "
            f"result covering only the first part of what you sent, recorded as covering all of "
            f"it, would be a layer whose source is not what it says it is."
        )


def _check_languages(
    kind: LayerKind,
    *,
    language: str | None,
    source_language: str | None,
    target_language: str | None,
) -> dict[str, str | None]:
    """The three language columns, refused where they would say something untrue.

    **A TRANSLATION MUST NAME BOTH ENDS, AND THAT IS THE ONE LANGUAGE RULE THIS MODULE ENFORCES
    RATHER THAN RECORDS.** "In English" says nothing about whether the artisan spoke Odia, Hindi or
    both, and that is precisely what a reader checking a translated passage has to know before they
    can check anything. A translation row without its source language is a row nobody can audit, so
    it is refused at the point where it would be written rather than discovered when somebody tries
    to trace it.

    ``multi`` IS A LEGITIMATE SOURCE LANGUAGE and is deliberately not refused: Deepgram Nova-3 is
    called with ``language=multi`` because a workshop is Hindi code-switched with English
    mid-sentence, so for much of this archive the honest answer to "what language was it in" really
    is "several, interleaved". What is refused is a translation INTO ``multi``, which is not a
    request anybody can act on — a target language is a choice the caller makes, not an observation.

    ``language`` IS FILLED FROM ``target_language`` FOR A TRANSLATION rather than left to the caller.
    It means "the language this layer's own text is in", which for a translation is by definition the
    target; letting the two be set independently would allow a row saying it is in Odia while its
    target language says English, and every reader would have to guess which column to believe.

    THE PAIR IS REFUSED ON EVERY OTHER KIND. A caption with a ``targetLanguage`` would be claiming a
    translation happened; a proofread with one would be claiming the language changed, which is the
    single thing a proofread promises not to do.
    """
    plain = (language or "").strip() or None
    source_lang = (source_language or "").strip() or None
    target_lang = (target_language or "").strip() or None

    if kind is not LayerKind.TRANSLATION:
        if source_lang or target_lang:
            raise LayerRuleViolation(
                f"A {kind.value} is not a translation, so it records one language and not a pair. "
                f"Send `language` alone — or register a TRANSLATION if the words did change "
                f"language, which is a separate layer standing beside this one."
            )
        return {"language": plain, "sourceLanguage": None, "targetLanguage": None}

    if not target_lang:
        raise LayerRuleViolation(
            "A translation must say which language it is INTO. Send targetLanguage — a translated "
            "passage in a report whose language nobody recorded cannot be checked against the "
            "original by anybody."
        )
    if target_lang.lower() == "multi":
        raise LayerRuleViolation(
            "'multi' is something a recording can BE, not something a translation can be INTO. "
            "Name the one language the text was translated into."
        )
    if not source_lang:
        raise LayerRuleViolation(
            "A translation must say which language it came FROM. Send sourceLanguage, or "
            f"'{UNRECORDED}' in that word if the run genuinely did not detect one — a reader who "
            "wants to check the translation against what the artisan said has to know what they "
            "said it in. 'multi' is a real answer here: these interviews code-switch mid-sentence."
        )
    if plain and plain != target_lang:
        raise LayerRuleViolation(
            f"This layer says its text is in {plain} and that it was translated into {target_lang}. "
            f"They are the same fact and cannot disagree — send targetLanguage alone and leave "
            f"language out."
        )
    # `language` mirrors the target deliberately: a query or a client that knows only about
    # `language` is then not silently wrong about a translation. See the docstring.
    return {"language": target_lang, "sourceLanguage": source_lang, "targetLanguage": target_lang}


def _check_chain(kind: LayerKind, source: LayerSource) -> None:
    """Refuse a rung that cannot sit where it is being put.

    The refusals name the chain rather than the rule number, because the person reading them is
    holding a recording and a screen, not this file.

    **DISPATCHED ON THE SOURCE AND NOT ON THE KIND, WHICH IS A RESTRUCTURE AND NOT A TIDY-UP.** The
    original read "if this kind is media-rooted it must come from media, otherwise it must come from
    a layer", which was exact while every kind belonged to exactly one of two sets. It stopped being
    exact the moment ``PROOFREAD`` and ``TRANSLATION`` became legitimate over BOTH a stored layer and
    a passage the caller supplied — a kind can now sit in two of the three sets, so the question has
    to be asked from the source's end. Every refusal the old shape produced is still produced here,
    which is what ``test_a_raw_rung_must_come_from_media_and_the_rungs_above_it_must_not`` exists to
    keep true.
    """
    if source.kind is SourceKind.MEDIA:
        if kind not in MEDIA_ROOTED_KINDS:
            raise LayerRuleViolation(
                f"A {kind.value} is produced from another layer, not straight from a media file. "
                f"Register the transcript or the OCR text first, then derive this from it — an "
                f"annexure that prints a conclusion has to be able to show what it was drawn from."
            )
        return

    if source.kind is SourceKind.SUPPLIED_TEXT:
        if kind not in TEXT_ROOTED_KINDS:
            raise LayerRuleViolation(
                f"A {kind.value} is not produced from words sent with the request. It may sit on: "
                f"{_where_it_may_sit(kind)}."
            )
        return

    if kind in MEDIA_ROOTED_KINDS and kind not in ALLOWED_PARENTS:
        raise LayerRuleViolation(
            f"A {kind.value} is produced from a recording or a photograph, not from another "
            f"layer. Derive it from the media file itself."
        )
    allowed = ALLOWED_PARENTS.get(kind, frozenset())
    if not allowed:
        raise LayerRuleViolation(
            f"Nothing may be derived from another layer as a {kind.value}. It may sit on: "
            f"{_where_it_may_sit(kind)}."
        )
    parent = source.layer_kind
    if parent is None:
        raise LayerRuleViolation(
            f"A {kind.value} must name the KIND of the layer it was produced from, because what "
            f"may sit above a rung depends on which rung it is. Load the source layer and pass its "
            f"kind."
        )
    if parent not in allowed:
        # `parent` is a kind, not None — the check three lines up guarantees it, and an earlier
        # draft's `parent.value if parent else 'nothing'` here was a branch nothing could reach.
        # `allowed` is non-empty for the same reason: the "nothing may be derived from another layer
        # as a …" refusal above now catches that case with a sentence that can name the OTHER places
        # the kind may sit, which a bare "it may sit above: nothing" could not. So both of the
        # defensive fallbacks this line used to carry are gone, and their absence is the point —
        # a message that hedges about its own data is a message nobody can act on.
        raise LayerRuleViolation(
            f"A {kind.value} cannot be derived from a {parent.value}. "
            f"It may sit on: {_where_it_may_sit(kind)}."
        )


def _check_content(kind: LayerKind, *, text: str | None, payload: Any) -> None:
    """Every kind must carry its principal content; the other slot is optional.

    A layer with nothing in it is a provenance record for nothing: it takes a row, a heading and an
    index line in the annexure and prints nothing underneath. The database refuses the empty case
    too (``DwAiLayer_has_content``); this refuses the subtler one, a SUMMARY whose prose was put in
    ``payload`` where no renderer will look for it.

    The secondary slot is permitted rather than forbidden, and that is not laziness: a Tier 3
    transcript legitimately has structure beside its prose — Scribe v2 diarizes up to 32 speakers,
    and those turns belong with the text they came from rather than in a second row that would have
    to be kept in step with it.
    """
    has_text = bool((text or "").strip())
    has_payload = payload is not None
    if kind in TEXT_KINDS and not has_text:
        raise LayerRuleViolation(
            f"A {kind.value} is prose and this one has none. Send the text the model produced."
        )
    if kind in STRUCTURED_KINDS and not has_payload:
        raise LayerRuleViolation(
            f"A {kind.value} is structured and this one carries no payload. Send the tags, fields "
            f"or values the model produced; a human-readable rendering may come with it as text."
        )


def transcript_rungs(
    *, transcript_text: str | None, transcript_summary: str | None
) -> tuple[str, str | None]:
    """Split what a ``MediaFile`` stores into the rungs it actually represents: (raw, cleaned).

    **THIS FUNCTION EXISTS BECAUSE ``transcriptText`` IS NOT NECESSARILY A RAW TRANSCRIPT, AND
    REGISTERING IT AS ONE WOULD PRINT A LIE UNDER AN ANNEXURE HEADING.** Read
    ``media_queue.transcribe_media_now`` and its worker twin: when ``AppSetting.transcriptionMode``
    is REFINED or REFINED_TRANSLATED — and **REFINED_TRANSLATED is the default** — the provider's
    text is passed through ``ai.refine_transcript_text``, which rewrites it into a clean
    interviewer/interviewee dialogue and, under REFINED_TRANSLATED, TRANSLATES IT INTO ENGLISH.
    ``_transcript_write`` then stores the rewritten form in ``transcriptText`` and the provider's own
    text in ``transcriptSummary``.

    So on a default deployment the column an annexure prints is a Tier 3 CLEANED_TRANSCRIPT in this
    module's vocabulary — model prose, possibly in a language the artisan did not speak — and
    labelling it "raw transcript" would be exactly the failure the plan's rule 4 is about: an
    AI-cleaned passage in a government document that is not identifiable as one.

    HOW "WAS IT REWRITTEN" IS DECIDED, without depending on a global setting that may have changed
    since the row was written: if the provider's text appears VERBATIM inside the stored text, the
    stored text is the provider's (``ai._transcription_result`` wraps it as ``"Transcript\\n\\n…"``,
    a header and nothing else) and there is one rung. If it does not, something rewrote it, and there
    are two. Nothing here reads ``transcriptionMode``, because the mode in force TODAY says nothing
    about the mode in force when a transcript from March was written — and guessing from it would be
    inventing a fact about a row.

    **"SOMETHING REWROTE IT" IS AS FAR AS THE EVIDENCE GOES, AND IT IS NOT ALWAYS A MODEL.**
    ``POST /media/{id}/transcript`` writes ``transcriptText`` and leaves ``transcriptSummary``
    exactly as the queue wrote it (``api/routes/media.py:641``), so a transcript a PERSON typed or
    corrected produces the same two-rung shape as a model refinement, and the row holds nothing that
    tells them apart — no column records who or what last wrote that text. The second rung is
    therefore "this is not the provider's own words", not "a model wrote this", and the caller must
    record its provider and model as :data:`UNRECORDED` rather than naming a refiner it cannot know
    ran. The failure this avoids is the same one in the other direction: an annexure heading calling
    an artisan's own corrections AI-cleaned prose.

    THE OTHER RESIDUAL UNKNOWN: a row with a ``transcriptText`` and NO ``transcriptSummary`` —
    written before the pair existed, or typed in by a person against a clip that was never
    transcribed — cannot be classified at all, because the comparison is the only evidence there is.
    It is returned as the raw rung with no cleaned rung, which is the weakest claim available rather
    than a confident wrong one. What the annexure will print over it depends on the provenance the
    CALLER passes; the route that registers stored transcripts passes :data:`UNRECORDED` unless an
    operator states otherwise, and this function neither sets nor sees that.
    """
    stored = (transcript_text or "").strip()
    provider_text = (transcript_summary or "").strip()
    if not stored and not provider_text:
        raise LayerRuleViolation(
            "This recording has no transcript to register. Wait for the transcription to finish — "
            "the transcripts screen shows where it is up to."
        )
    if not provider_text:
        return stored, None
    if not stored or provider_text in stored:
        return provider_text, None
    return provider_text, stored


def duplicate_of(
    existing: Iterable[Any],
    *,
    source: LayerSource,
    kind: LayerKind,
    tier: AiTier,
    provider: str,
    model_id: str,
) -> Any | None:
    """The live layer this create would repeat, or None.

    WHAT IS AND IS NOT A DUPLICATE, because getting this wrong loses the feature. Two layers over
    the same source with DIFFERENT tiers or different models are not duplicates — they are the whole
    point: the plan's §2.1 safeguard is that a phone-produced and a cloud-produced transcript of the
    same audio must both exist and be tellable apart on the page. A unique index on
    (source, kind) would have forbidden exactly that, which is why there is none.

    What is a duplicate is the same source, kind, tier, provider AND model registered twice, which
    would put the identical text into one annexure twice under two headings.

    WHAT IT CATCHES IS A REPEAT, NOT A RACE, and the difference is worth being exact about because
    the obvious sentence to write here — "this is what a double tap produces" — is not true. The
    caller reads the existing layers and then writes, and two requests that overlap in that gap both
    read nothing and both write. On the link this repository measured at 756ms a round trip a
    genuine double tap overlapped easily; production is co-located since 2026-09-02 and a hop is one
    or two milliseconds (``services/concurrency``), which makes the window SMALLER AND NOT CLOSED —
    and a window that is merely small is the one that produces a bug nobody can reproduce. Nothing
    below rests on its size. The gap is not closed by a
    unique index, and deliberately: the rule is only expressible as a PARTIAL unique index on
    (source, kind, tier, provider, model) WHERE deletedAt IS NULL — ``@@unique`` can say the columns
    but Prisma has no syntax for the WHERE, and without it a declined row would forbid ever
    registering the same transcript again, which :func:`deletion_plan` deliberately allows. That
    leaves raw SQL the schema cannot see, and migration 20260808140000 recorded why that is not
    worth the permanent drift it causes. What stands instead is that nothing is printed
    unless a person accepts it: two identical rows reach a report only if somebody accepts both,
    with both in front of them.

    Deleted rows are ignored: re-registering something that was declined is a deliberate act and the
    old row stays as the record that it was declined.
    """
    for row in existing:
        if getattr(row, "deletedAt", None) is not None:
            continue
        try:
            row_source = source_of(row)
            same = (
                row_source.kind is source.kind
                and row_source.id == source.id
                # THE WORDS ARE PART OF THE IDENTITY FOR A SUPPLIED-TEXT SOURCE, and leaving them out
                # was a real defect in the first draft of this widening: both rows carry an EMPTY id,
                # so two different field notes proofread by the same model on the same day compared
                # equal, and the second designer's note was refused as a duplicate of the first's.
                # Compared stripped, because that is the form `LayerSource.columns` stores.
                and (row_source.text or "").strip() == (source.text or "").strip()
                and _kind_of(getattr(row, "kind", "")) is kind
                and _tier_of(getattr(row, "tier", "")) is tier
                and (getattr(row, "provider", "") or "") == provider
                and (getattr(row, "modelId", "") or "") == model_id
            )
        except ValueError:
            # Two causes, one answer. A row with no usable source raises `LayerRuleViolation`; a row
            # whose stored kind or tier is not in the vocabulary raises a plain `ValueError` out of
            # the enum — which the database's own enum columns should make impossible, and which
            # would arrive here as a 500 rather than as a comparison that simply cannot be made.
            # Either way, a row somebody else wrote badly must not block the designer in front of
            # us: it is skipped, and logged so it is findable.
            logger.warning("ai_layers: layer %s cannot be compared", getattr(row, "id", "?"))
            continue
        if same:
            return row
    return None


# --------------------------------------------------------------------------------------
# Rule 3: acceptance, which is a transition with an audit and not a boolean
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class DecisionPlans:
    """The two writes one decision makes: the state on the layer, and the row in the log.

    They are returned together and must be applied together. The log is the authoritative history —
    it is what keeps "who stood behind this text on the 3rd" answerable after a withdrawal on the
    11th, by which time a document naming the acceptance is already in an officer's hands — and the
    columns on the layer are the current state the report builder reads without walking a log per
    layer per render.
    """

    layer: LayerWritePlan
    decision: LayerWritePlan

    def __iter__(self):
        yield self.layer
        yield self.decision


def acceptance_plan(
    row: Any, *, actor_id: str, at: datetime, note: str | None = None
) -> DecisionPlans:
    """A person puts their name to this layer.

    Refused for a deleted layer and for one already accepted — the second is not pedantry: a second
    acceptance would overwrite the first acceptor's name with the second's, which is the one fact
    about an accepted layer that a reader of a submitted report may need to trace.

    THE REFUSAL IS READ-THEN-WRITE AND THEREFORE SEQUENTIAL, which is worth stating rather than
    implying: two people accepting the same layer in the same instant both read an unaccepted row
    and both write, and the columns end up naming whichever landed second. What survives that is the
    log — :class:`DwAiLayerDecision` gains a row per acceptance, with both names and both moments —
    so the question "who stood behind this text" stays answerable even in the case this check misses.
    Closing the gap properly means a conditional update (``acceptedAt IS NULL`` in the WHERE), which
    is a different write shape from the one rule 1's test pins to ``{"id": ...}``; it is a
    deliberate later change, not an oversight.
    """
    layer_id = _live_layer_id(row, verb="accepted")
    if getattr(row, "acceptedAt", None) is not None:
        raise LayerRuleViolation(
            "This layer has already been accepted. Withdraw the acceptance first if it should stand "
            "in somebody else's name."
        )
    actor = _require_text(
        actor_id,
        what="An acceptance records who made it.",
        remedy="Sign in and accept it as yourself — an unsigned acceptance is not one.",
    )
    return DecisionPlans(
        layer=LayerWritePlan(
            table="DwAiLayer",
            operation=Operation.UPDATE,
            where={"id": layer_id},
            data={"acceptedAt": at, "acceptedById": actor},
        ),
        decision=_decision_plan(layer_id, Decision.ACCEPTED, actor, note),
    )


def withdrawal_plan(row: Any, *, actor_id: str, note: str | None = None) -> DecisionPlans:
    """A person takes their name off this layer. The layer itself is untouched and stays readable.

    The acceptance columns are cleared and the LOG is not, deliberately. A report generated while
    the layer was accepted named it as accepted, and that document does not change because somebody
    changed their mind afterwards; the log is what still explains it.
    """
    layer_id = _live_layer_id(row, verb="withdrawn")
    if getattr(row, "acceptedAt", None) is None:
        raise LayerRuleViolation(
            "This layer has not been accepted, so there is nothing to withdraw. Delete it instead "
            "if it should not be offered again."
        )
    actor = _require_text(
        actor_id,
        what="A withdrawal records who made it.",
        remedy="Sign in and withdraw it as yourself.",
    )
    return DecisionPlans(
        layer=LayerWritePlan(
            table="DwAiLayer",
            operation=Operation.UPDATE,
            where={"id": layer_id},
            data={"acceptedAt": None, "acceptedById": None},
        ),
        decision=_decision_plan(layer_id, Decision.WITHDRAWN, actor, note),
    )


def _decision_plan(
    layer_id: str, decision: Decision, actor_id: str, note: str | None
) -> LayerWritePlan:
    return LayerWritePlan(
        table="DwAiLayerDecision",
        operation=Operation.CREATE,
        data={
            "layerId": layer_id,
            "decision": decision.value,
            "note": (note or "").strip() or None,
            "actorId": actor_id,
        },
    )


# --------------------------------------------------------------------------------------
# Rule 5: deleting a derived layer never touches its source
# --------------------------------------------------------------------------------------


def deletion_plan(
    row: Any, *, actor_id: str | None, at: datetime, derived: Sequence[Any] = ()
) -> LayerWritePlan:
    """Decline a layer. ONE plan, ONE row id, TWO columns — and nothing about its source.

    Rule 5 is enforced by what this function cannot express. It returns a single plan; that plan's
    ``where`` names the layer's own id; that plan's ``data`` sets ``deletedAt`` and ``deletedById``
    and nothing else. There is no branch here that reads ``sourceMediaId`` or ``sourceLayerId``, and
    ``tests/test_ai_layers.py`` asserts all of it — so a later change that "also tidies up the
    recording" fails a test rather than deleting the evidence a transcript was made from.

    A SOFT DELETE, matching ``DesignWorkshop.deletedAt`` and ``DwStageEntry.deletedAt`` and for the
    same stated reason: a designer's two weeks of fieldwork is not something a mis-tap should end.
    Here it buys something extra — a declined suggestion stays on record as a declined suggestion,
    which is the only way "the model proposed this and a person said no" survives at all.

    A LAYER WITH LIVE LAYERS ABOVE IT IS REFUSED, NAMING THEIR KINDS. Deleting a raw transcript
    while a cleaned transcript still derives from it leaves the cleaned one pointing at something no
    screen will show, so its annexure line could no longer say what it was made from. That is the
    opposite of the traceability this table exists for. The kinds and not the ids, because the
    person reading the refusal is looking at a list of headings rather than at a table of cuids —
    and read defensively, since a row whose stored kind is outside the vocabulary must produce a
    worse sentence rather than a 500 in the middle of a refusal.
    """
    layer_id = _live_layer_id(row, verb="deleted")
    live = [d for d in derived if getattr(d, "deletedAt", None) is None]
    if live:
        kinds = sorted({_enum_str(getattr(d, "kind", None)) or "layer" for d in live})
        raise LayerRuleViolation(
            f"{len(live)} layer(s) were produced from this one ({', '.join(kinds)}) and would be "
            f"left describing something no longer on screen. Delete those first, or leave this one "
            f"in place — nothing here is printed unless it has been accepted."
        )
    return LayerWritePlan(
        table="DwAiLayer",
        operation=Operation.UPDATE,
        where={"id": layer_id},
        data={"deletedAt": at, "deletedById": actor_id or None},
    )


def _live_layer_id(row: Any, *, verb: str) -> str:
    layer_id = _require_text(
        getattr(row, "id", ""),
        what="That layer could not be identified.",
        remedy="Reload the workshop's layers and try again.",
    )
    if getattr(row, "deletedAt", None) is not None:
        raise LayerRuleViolation(
            f"This layer has been deleted and cannot be {verb}. Register it again if the material "
            f"is still wanted — the deleted row stays as the record that it was declined."
        )
    return layer_id


# --------------------------------------------------------------------------------------
# The wire
# --------------------------------------------------------------------------------------


def layer_payload(
    row: Any, *, include_text: bool = False, text_withheld: bool = False
) -> dict[str, Any]:
    """One layer as the clients read it. camelCase on the wire, snake_case in Python.

    ``text`` is present only when the caller asked for it — see :data:`PREVIEW_CHARS` for the size
    this saves — but ``preview`` and ``textChars`` are otherwise always there, so a list can say how
    much there is to read without carrying it.

    ``payload`` is otherwise always included: tags and extracted fields are small by nature, and they
    are the principal content of the kinds that carry them, so omitting them would leave those rows
    looking empty in exactly the list a designer is scanning to decide what to accept.

    **``text_withheld`` IS THE MEDIA GATE ARRIVING HERE, AND IT COVERS FOUR KEYS, NOT ONE.** A
    layer's content is a copy of a transcript, and a transcript is the CONTENT of a recording: who
    may read one is decided per file by ``owned_or_granted_where(user, owner_field="uploadedById")``
    and NOT by who may open the workshop. Those two sets differ — a ``DesignWorkshopViewer`` grant
    carries read and stage writes and says nothing about media — so without this the list would hand
    a granted colleague the full transcript of a recording ``GET /design-workshops/{id}/transcripts``
    refuses them, out of a table whose whole purpose is to keep a copy. ``preview``, ``textChars``,
    ``payload`` and ``text`` all go: a 280-character opening is not less of a transcript for being
    short, and a diarized payload is the same speech in another shape.

    THE PROVENANCE STAYS, AND THAT IS THE POINT OF WITHHOLDING RATHER THAN OMITTING THE ROW. Which
    tier and which model produced a layer, and who accepted it, is what the plan says a reviewer
    opens this screen for (§2.1: a cloud-diarized interview and a device-guessed one must never look
    alike on a page), and none of it is the recording's content. ``textWithheld`` is on EVERY payload
    rather than only on the withheld ones, so a client renders "you cannot read this one" from a
    stated fact instead of inferring it from an empty preview — the same reason ``accepted`` is an
    explicit boolean beside a nullable timestamp.
    """
    text = getattr(row, "text", None) or ""
    accepted_at = getattr(row, "acceptedAt", None)
    source = None
    try:
        stored = source_of(row)
        source = {
            "kind": stored.kind.value,
            # Null rather than "" for a supplied-text source: there is no row to name, and an empty
            # string is the shape a client renders as a link to nothing.
            "id": stored.id or None,
            # THE EVIDENCE TRAVELS WITH THE LAYER. A caller looking at a proofread has to be able to
            # see what was proofread, and for this source kind there is no second request that could
            # fetch it — the words exist only here. Withheld along with everything else when the
            # media gate says so, which for a supplied-text root it never does (see
            # `ChainRoot.withheld_from`); the key is present either way so a client renders a stated
            # fact rather than inferring one from an absence.
            "text": None if text_withheld else stored.text,
        }
    except LayerRuleViolation:
        # A malformed row is rendered with a null source rather than crashing the whole list. The
        # alternative — one bad row taking out the screen — hides the other twenty-four transcripts
        # a designer came to look at.
        logger.warning("ai_layers: layer %s has no usable source", getattr(row, "id", "?"))

    payload: dict[str, Any] = {
        "id": getattr(row, "id", None),
        "designWorkshopId": getattr(row, "designWorkshopId", None),
        "kind": _enum_str(getattr(row, "kind", None)),
        "tier": _enum_str(getattr(row, "tier", None)),
        "source": source,
        "provider": getattr(row, "provider", None),
        "modelId": getattr(row, "modelId", None),
        "modelVersion": getattr(row, "modelVersion", None),
        "language": getattr(row, "language", None),
        # Null on every kind but a translation, and null there too if this row predates the columns.
        # Sent unconditionally rather than only on translations: a client that has to look at `kind`
        # before it knows whether a key exists is a client that will one day read the wrong branch,
        # and the pair is the fact a reviewer of a translated passage opens the screen for.
        "sourceLanguage": getattr(row, "sourceLanguage", None),
        "targetLanguage": getattr(row, "targetLanguage", None),
        "producedAt": _iso(getattr(row, "producedAt", None)),
        "createdAt": _iso(getattr(row, "createdAt", None)),
        "createdById": getattr(row, "createdById", None),
        # Rule 3 on the wire. A client must be able to render "not accepted" without inferring it
        # from a null timestamp, because "no acceptance recorded" and "accepted, timestamp missing"
        # would look identical and only one of them may be printed in a report.
        "accepted": accepted_at is not None,
        "acceptedAt": _iso(accepted_at),
        "acceptedById": getattr(row, "acceptedById", None),
        # Null rather than 0 when it is withheld: 0 would say "there is nothing to read", which is a
        # different fact from "you may not read it" and the only one of the two that is false here.
        "textChars": None if text_withheld else (len(text) if text else 0),
        "preview": None if text_withheld else _preview(text),
        "payload": None if text_withheld else getattr(row, "payload", None),
        "textWithheld": text_withheld,
        "deletedAt": _iso(getattr(row, "deletedAt", None)),
    }
    if include_text and not text_withheld:
        payload["text"] = text or None
    return payload


def decision_payload(row: Any) -> dict[str, Any]:
    """One acceptance or withdrawal as the clients read it."""
    return {
        "id": getattr(row, "id", None),
        "layerId": getattr(row, "layerId", None),
        "decision": _enum_str(getattr(row, "decision", None)),
        "note": getattr(row, "note", None),
        "actorId": getattr(row, "actorId", None),
        "createdAt": _iso(getattr(row, "createdAt", None)),
    }


def _preview(text: str) -> str | None:
    """The opening of a layer, on one line, so a list can be scanned.

    Newlines are collapsed because a transcript's first line is often a speaker label on its own
    (``**Interviewer:**``), and a preview that showed only that would distinguish nothing.
    """
    flat = " ".join((text or "").split())
    if not flat:
        return None
    if len(flat) <= PREVIEW_CHARS:
        return flat
    return flat[:PREVIEW_CHARS].rstrip() + "…"


def _enum_str(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


# --------------------------------------------------------------------------------------
# The database half: loaders for this API and for the report lane, and the plan executor
#
# Everything above this line is pure and is what tests/test_ai_layers.py exercises. Everything below
# is a thin call site: it reads rows, or it applies a plan built above. No rule is decided here.
# --------------------------------------------------------------------------------------


def _writable_model(table: str) -> Any:
    """The Prisma model one writable table name maps to.

    THE SECOND HALF OF THE CONSTRUCTION GUARD. There is no entry here for ``DwStageEntry``, so even
    a plan that somehow carried its name — a future edit that loosened :class:`LayerWritePlan` —
    would still have nowhere to be applied.

    THE NAME IS CHECKED BEFORE THE CLIENT IS TOUCHED, deliberately: the refusal is then a
    :class:`LayerRuleViolation` with a sentence, on any machine, with or without a generated Prisma
    client — which is what lets ``tests/test_ai_layers.py`` push on this door with no database
    underneath it. Resolved per call rather than in a module-level dict because a dict built at
    import binds the client attributes before anything has connected.
    """
    if table not in WRITABLE_TABLES:
        raise LayerRuleViolation(
            f"There is no AI-layer writer for {table}. AI output is annexure content and a "
            f"suggestion; it is written to {', '.join(sorted(WRITABLE_TABLES))} and nowhere else."
        )
    return {"DwAiLayer": db.dwailayer, "DwAiLayerDecision": db.dwailayerdecision}[table]


#: table -> the columns that are Postgres ``Json`` and must be wrapped before they are written.
#:
#: A RAW dict OR list REACHING A Json COLUMN IS A 500, NOT A 422: the Prisma driver raises rather
#: than validating, so the caller sees "something went wrong on the server" for a payload that was
#: perfectly good. ``design_workshops._json`` exists for exactly this reason and carries the same
#: note; this is the same fix for this table's one Json column.
JSON_COLUMNS: Mapping[str, tuple[str, ...]] = {"DwAiLayer": ("payload",)}


def _json_ready(table: str, data: Mapping[str, Any]) -> dict[str, Any]:
    """``data`` with this table's Json columns wrapped for the driver — ``None`` included.

    **A None IS WRAPPED, AND LEAVING IT ALONE WAS A 500 ON EVERY PROSE LAYER EVER REGISTERED.** This
    function used to skip it, on the reasoning that ``Json(None)`` writes a JSON *null* while a bare
    ``None`` writes SQL NULL, and that "they read back differently". Measured against this
    deployment's Postgres, through this driver, BOTH of those sentences are false:

    * A bare ``None`` does not write SQL NULL. It does not write anything. prisma-client-py renders
      it as ``payload: null`` and the query engine refuses ``null`` for a nullable ``Json`` column —
      ``MissingRequiredValueError: `data.payload`: A value is required but not set``. Since
      :func:`layer_create_plan` puts ``"payload": payload`` in unconditionally and ``payload``
      defaults to ``None``, EVERY layer with prose and no structure — which is every
      ``CLEANED_TRANSCRIPT``, ``TRANSLATION``, ``PROOFREAD`` and ``EXPANDED`` row — could not be
      written at all.
    * They do not read back differently. ``Json(None)`` comes back out of this driver as Python
      ``None``, which is what an SQL NULL comes back as too, and nothing in this repository queries
      either column with a raw ``IS NULL`` that could tell them apart.

    So the distinction the old note protected was not available to protect, and insisting on it cost
    the whole table. This is the same defect, in the same driver, as the one that made
    ``PUT /custom-sections`` a 500 for every body containing a field; see
    ``custom_sections._field_columns``, which now carries the long version of the argument.

    WRAPPING RATHER THAN DROPPING THE KEY, because this helper serves ``apply_plan``'s UPDATE branch
    as well as its CREATE branch. Dropping it is right on a create and is a silent no-op on an
    update — a payload the caller meant to clear staying where it was, under a 200. No update writes
    a Json column today; the point is that the next one to be added cannot introduce that bug here.

    Kept out of :func:`layer_create_plan` deliberately: the plan stays plain data so tests can
    assert what will be written without a driver in the way, and the wrapping is a transport
    concern that belongs at the point of transport.
    """
    from prisma import Json

    out = dict(data)
    for column in JSON_COLUMNS.get(table, ()):
        if column in out:
            out[column] = Json(out[column])
    return out


async def apply_plan(plan: LayerWritePlan) -> Any:
    """Perform one planned write. The only place this module touches the database with intent."""
    model = _writable_model(plan.table)
    data = _json_ready(plan.table, plan.data)
    if plan.operation is Operation.CREATE:
        return await model.create(data=data)
    return await model.update(where=dict(plan.where or {}), data=data)


async def apply_decision(plans: DecisionPlans) -> Any:
    """Apply a decision's two writes: the layer's state first, then the log entry.

    NOT IN A TRANSACTION, and the order is chosen for the failure that leaves. If the log write
    fails after the state write, the layer is accepted with one missing history row — recoverable,
    and the report still names the right acceptor. The other order would leave a log saying somebody
    accepted a layer that is not accepted, which is a document-explaining record that contradicts the
    document. Prisma's Python client does expose an interactive transaction; using one here is a
    reasonable later change, but it is not free on a single-worker deployment and this ordering makes
    the worst case survivable rather than confusing.
    """
    layer = await apply_plan(plans.layer)
    await apply_plan(plans.decision)
    return layer


async def workshop_layers(workshop_id: str, *, include_deleted: bool = False) -> list[Any]:
    """Every layer of one workshop, newest first.

    Filters ``deletedAt: null`` by default, exactly as ``entry_rows`` does for stage entries.

    NO ``kind`` FILTER HERE, DELIBERATELY, AND IT IS NOT AN OVERSIGHT — narrowing this read is what
    breaks the entitlement check that stands on it. Whether a caller may read a layer's TEXT is
    decided by the recording at the foot of its chain, and a SUMMARY only names the
    CLEANED_TRANSCRIPT it stands on. Ask the database for one kind and the parents are missing, the
    chain cannot be walked, and :func:`media_root` fails closed — blanking the text of rows the
    caller was entitled to. The list route therefore loads the workshop's layers whole and narrows
    with :func:`is_kind` in Python, which is free at the size of one workshop. The kind narrowing
    that does reach SQL is :func:`accepted_layers`', where the report asks for one kind of accepted
    layer and walks no chain.
    """
    where: dict[str, Any] = {"designWorkshopId": workshop_id}
    if not include_deleted:
        where["deletedAt"] = None
    return await db.dwailayer.find_many(where=where, order={"createdAt": "desc"})


async def accepted_layers(workshop_id: str, *, kind: LayerKind | None = None) -> list[Any]:
    """The layers a person has accepted — rule 4's input, and the report lane's entry point.

    THE REPORT MUST NOT DECIDE FOR ITSELF WHAT "ACCEPTED" MEANS. If step 3's renderer wrote its own
    ``acceptedAt is not None`` filter, then the day acceptance grows a condition — an expiry, a
    second signature, a withdrawal that has to be honoured mid-render — the report would keep
    printing by the old rule and nothing would say so. One definition, here.

    **THIS READ IS NOT ENTITLEMENT-FILTERED, AND ITS CALLER MUST BE.** It answers "what has been
    accepted in this workshop" and nothing about who is asking. A report is generated by anybody who
    can READ the workshop, while the recordings it embeds are gated per file — that is why
    ``append_transcript_annexure`` takes a ``viewer`` and why ``load_transcript_items``' docstring
    calls the ungated version a leak. Step 3's renderer must put a layer's text through the same gate
    before printing it: :func:`media_roots` gives the recording each layer stands on, and
    ``owned_or_granted_where(user, owner_field="uploadedById")`` is the predicate every other
    download surface already uses.
    """
    # The field-level negation (`map_points`, `design_workshops`, `media` all spell it this way)
    # rather than a top-level "NOT", which would sit oddly beside the two plain keys next to it.
    where: dict[str, Any] = {
        "designWorkshopId": workshop_id,
        "deletedAt": None,
        "acceptedAt": {"not": None},
    }
    if kind is not None:
        where["kind"] = kind.value
    return await db.dwailayer.find_many(where=where, order={"acceptedAt": "desc"})


async def layers_from_media(workshop_id: str, media_id: str) -> list[Any]:
    """Everything already derived from one recording, live or deleted.

    Deleted rows are included because :func:`duplicate_of` needs to see the difference between "this
    was never registered" and "this was registered and declined", and it decides differently.
    """
    return await db.dwailayer.find_many(
        where={"designWorkshopId": workshop_id, "sourceMediaId": media_id}
    )


async def derived_from(layer_id: str) -> list[Any]:
    """The live layers produced from this one — what :func:`deletion_plan` is given to refuse on."""
    return await db.dwailayer.find_many(where={"sourceLayerId": layer_id, "deletedAt": None})


async def layer_in_workshop(workshop_id: str, layer_id: str) -> Any | None:
    """One live layer, but only if it belongs to this workshop.

    THE WORKSHOP CHECK IS NOT DECORATION. Every route reaches a layer through a workshop id that
    ``load_workshop_or_404`` has already admitted the caller to; without this comparison, a layer id
    from a workshop the caller cannot open could be accepted, withdrawn or deleted through a
    workshop they can. The id is a cuid and unguessable, which makes it unlikely rather than
    impossible, and "unlikely" is not an access rule.
    """
    row = await db.dwailayer.find_unique(where={"id": layer_id})
    if row is None or row.deletedAt is not None:
        return None
    if row.designWorkshopId != workshop_id:
        return None
    return row


async def layer_decisions(layer_id: str) -> list[Any]:
    """The acceptance history of one layer, oldest first."""
    return await db.dwailayerdecision.find_many(
        where={"layerId": layer_id}, order={"createdAt": "asc"}
    )
