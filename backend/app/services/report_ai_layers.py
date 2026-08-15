"""The AI annexure: what a model produced, printed at the back and NAMED as a model's work.

WHAT THIS IS FOR, AND WHY IT IS THE THIRD STEP RATHER THAN THE FIRST. Plan §3 rule 4:

    The report prints the accepted layer and names it as such. An AI-cleaned passage in a
    government document must be identifiable as one.

Everything under that sentence already exists. :mod:`app.services.ai_layers` holds the law — every
layer a row, provenance on each, inert until a person accepts it — and the acceptance screen puts a
name against one. This module is the last rung: it turns the accepted layers into blocks, and it is
the only place in this codebase where model prose is allowed to enter a document a ministry officer
reads. That is why the naming is not decoration and not a caption. It is the feature.

ONLY ACCEPTED LAYERS ARE PRINTED, and that is enforced here as well as upstream. The caller loads
through ``ai_layers.accepted_layers`` — the single definition of "accepted", written that way so the
report cannot grow a second opinion — and :func:`append_ai_layer_annexure` refuses an unaccepted item
again on the way in. Two guards for one rule, for the reason the source CHECK constraint has two: a
loader can be changed by somebody who has not read this file, and rule 3 is the rule that decides
whether a person's name stands behind a paragraph in a sanctioned report.

WHY A SEPARATE MODULE, like :mod:`app.services.report_annexures` before it.
:mod:`app.services.report_builder` is the generic template interpreter and is deliberately free of
per-feature code, so the builder reaches this from ONE branch of ``ReportBuilder.build`` and does
nothing else. That module's docstring records what happens when the branch is missing: the transcript
annexure was a complete, tested module with no call site, and every report silently dropped it while
three surfaces told the designer the office's copy would carry it. **A module with no call site is
not a feature**, and this one was written with that sentence in view.

WHY THE ITEMS TRAVEL ON ``WorkshopData``. Every renderer in this pipeline is synchronous so the
Kotlin port can be a transliteration rather than a reimplementation. A layer is a database read, so
it is loaded with everything else and carried in, never awaited mid-render.

WHAT THE HANDSET DOES ABOUT THIS SECTION: nothing, honestly. The layers live on the server and the
phone holds no copy, so ``ReportSettings.UNSUPPORTED_SECTIONS`` carries a sentence saying the field
copy cannot include them and the office's copy will. That is the ``ANNEXURE_TRANSCRIPTS`` precedent
and it applies here for the same reason it applied there.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.services.report_annexures import plain_text, transcript_body_blocks
from app.services.report_model import (
    Block,
    DocumentBuilder,
    PageBreakBlock,
    ParagraphBlock,
    ParaStyle,
    ReportMeta,
    Run,
    TableBlock,
    TableColumn,
    runs_of,
)

DEFAULT_HEADING = "Annexure — Machine-assisted text, and what produced it"

#: The same ceiling ``report_annexures`` puts on one transcript, and for the same reason: a provider
#: stuck in a repetition loop produces tens of thousands of paragraphs, and both renderers lay out
#: every one before the designer sees a page. Imported rather than restated would be better still,
#: but the transcript module's constant is about a RECORDING's length and this one is about a
#: MODEL's output — they are the same number today for the same practical reason and are free to
#: diverge, so they are two constants with two reasons rather than one with a footnote.
MAX_PARAGRAPHS_PER_LAYER = 1200


# --------------------------------------------------------------------------------------
# The vocabulary, in the words a reader of the document needs rather than the wire's
# --------------------------------------------------------------------------------------

#: ``DwAiLayerKind`` -> what to call it on the page.
#:
#: EVERY ONE OF THESE SAYS "AUTOMATIC" OR "AI" IN THE HEADING ITSELF, and that is rule 4 taken
#: literally. A heading reading "Cleaned transcript" is a heading a reader attributes to the person
#: who signed the report; the whole point of this annexure is that they must not. The word is in the
#: heading and not only in the provenance line below it, because a heading is what survives being
#: skimmed, quoted, or pasted into a covering note.
_KIND_TITLES: dict[str, str] = {
    "RAW_TRANSCRIPT": "Automatic transcript",
    "CLEANED_TRANSCRIPT": "AI-cleaned transcript",
    "SUMMARY": "AI summary",
    "OCR_TEXT": "Text read automatically from a photograph",
    "STRUCTURED_TEXT": "AI-structured text",
    "TAGS": "AI-suggested tags",
    "METADATA": "AI-extracted details",
    # The five verbs. Each heading says what the machine was allowed to change, because that is the
    # question a reader who is about to quote the passage actually has:
    "PROOFREAD": "AI-corrected spelling and punctuation",
    # NOT "AI-expanded note", which reads as a note that got longer. What happened is that a machine
    # WROTE sentences from a shorthand, and the heading has to say the strongest true thing about
    # that rather than the most comfortable one — see EXPANDED_NOTE below, which is printed under
    # this heading and under no other.
    "EXPANDED": "Prose written by AI from a designer's note",
    "TRANSLATION": "AI translation",
    "CAPTION": "AI description of a photograph or video",
    "SUBTITLES": "AI subtitles, with their timings",
}

#: ``DwAiTier`` -> which machine, in words.
#:
#: NOT "Tier 1 / 2 / 3" ON THE PAGE. The tier is the single most important fact in this annexure —
#: plan §2.1 says a cloud-diarized interview and a device-guessed one must never look alike on a
#: page — and a bare number communicates that to nobody outside this repository. An officer reading
#: a report has no reason to know what tier 2 is, and would not guess that tier 1 is the one that
#: works with no signal rather than the weakest one.
_TIER_WORDS: dict[str, str] = {
    "TIER_1": "on the handset, with no connection",
    "TIER_2": "on the handset, by a small model held on the device",
    "TIER_3": "on the server, by a hosted model",
}

#: What a missing provider or model id is stored as. Not a placeholder this module invented — the
#: column is NOT NULL and ``ai_layers`` writes this literal, because the media queue has never
#: recorded which of its four providers produced a transcript. Printed as a sentence rather than as
#: the token, because "UNRECORDED" in a ministry document reads as a broken export.
UNRECORDED = "UNRECORDED"


def kind_title(kind: str) -> str:
    """The heading for one layer kind.

    An unknown kind gets a heading that still says the text is machine-made. A newer server can
    write a kind this build has never heard of — the enum is a Postgres type and a deployment can be
    a release behind — and the failure that must not happen is a passage of model prose appearing in
    a government document under a heading that does not say so. Falling back to the raw token would
    do exactly that for any reader who does not recognise it.
    """
    return _KIND_TITLES.get(str(kind or "").upper()) or "Machine-generated text"


def tier_words(tier: str) -> str:
    """Which machine produced it, in words. An unknown tier is stated as unknown, never guessed."""
    return _TIER_WORDS.get(str(tier or "").upper()) or "by a machine this report cannot identify"


# --------------------------------------------------------------------------------------
# One layer, as the annexure reads it
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class AiLayerItem:
    """One accepted layer, already resolved from the database.

    A flat value object with no Prisma row inside it, exactly as ``TranscriptItem`` is, and for the
    same reason: the acceptance screen and the annexure must not be able to disagree about what a
    layer is. ``accepted`` is carried explicitly rather than inferred from ``accepted_at`` being
    non-null, because "no acceptance recorded" and "accepted, timestamp lost" would look identical
    and only one of them may be printed — the same distinction ``layer_payload`` draws on the wire.
    """

    layer_id: str
    kind: str = ""
    tier: str = ""
    provider: str = ""
    model_id: str = ""
    model_version: str = ""
    language: str = ""
    #: The two ends of a translation, blank on every other kind. Carried as a pair because "in
    #: English" is not a provenance record for a translation: a reader checking the passage against
    #: what the artisan said has to know what they said it IN, and that is the fact the single
    #: ``language`` field cannot hold.
    source_language: str = ""
    target_language: str = ""
    produced_at: str = ""
    accepted: bool = False
    accepted_at: str = ""
    #: THE ACCEPTOR'S NAME, when the caller resolved one. Blank when it did not — and blank is NOT
    #: printed as blank; see :attr:`acceptor`.
    #:
    #: THIS FIELD USED TO HOLD WHATEVER THE LOADER HAD, which in production is
    #: ``DwAiLayer.acceptedById`` — a cuid. So a .docx reaching a directorate carried "Accepted by
    #: cmld8x0a10000gzsy4t9v2b1q" against a paragraph of machine-written prose, beneath a lead
    #: paragraph asserting that a NAMED PERSON had reviewed it. The officer could not answer the one
    #: question rule 3 exists to keep answerable without database access.
    #:
    #: AND NO TEST COULD SEE IT, which is the part worth recording. The fixture in
    #: ``tests/test_report_ai_layers.py`` supplied ``"A. Designer"`` — a value no production caller
    #: can produce — so eighty tests asserted a name was printed while the only code path that fills
    #: this field passed an identifier. A fixture more capable than its subject is a test that cannot
    #: fail for the reason it was written.
    accepted_by: str = ""
    #: The acceptor's account id, kept beside the name rather than instead of it: the name is what a
    #: reader needs and the id is what an auditor needs, and one of them is not a substitute for the
    #: other. Always available — it is the column the acceptance wrote.
    accepted_by_id: str = ""
    source_kind: str = ""
    source_id: str = ""
    #: What the source is CALLED, when the caller could resolve it. Blank when it could not, and
    #: blank is printed as the id rather than as nothing — a reader tracing a summary back to the
    #: recording it came from needs something to trace it with.
    source_label: str = ""
    text: str = ""
    #: WHETHER THIS READER MAY READ THE RECORDING THIS LAYER STANDS ON.
    #:
    #: A layer's text is a copy of a transcript, and a transcript is the CONTENT of a recording —
    #: gated per file by ``owned_or_granted_where(user, owner_field="uploadedById")``, which is a
    #: different question from "may this account open the workshop". A ``DesignWorkshopViewer`` grant
    #: carries read and stage writes and says nothing at all about media, so the two sets differ in
    #: practice and not merely in theory.
    #:
    #: WITHHELD MEANS THE PROVENANCE STILL PRINTS AND THE BODY DOES NOT, which is deliberate and is
    #: the reason this is a flag rather than the loader simply omitting the item. Which tier and which
    #: model produced a passage, and who accepted it, is what a reviewer opens the annexure for and is
    #: not the recording's content; a layer silently absent is indistinguishable from one nobody made.
    #: It mirrors ``layer_payload``'s ``textWithheld`` on the wire, for the same reason and by the same
    #: predicate.
    text_withheld: bool = False

    @property
    def title(self) -> str:
        return kind_title(self.kind)

    @property
    def acceptor(self) -> str:
        """Who stood behind this text, in the most identifying form available — never a bare cuid.

        A NAME IF THERE IS ONE, AND OTHERWISE THE ID SAID TO BE AN ID. "Accepted by
        cmld8x0a10000gzsy4t9v2b1q" reads, to the officer holding the document, as a rendering
        failure; it is in fact a perfectly good audit reference that nobody told them was one. The
        difference between the two is one clause, and that clause is the whole fix: a reader who
        cannot resolve it at least knows what it is and that it identifies somebody, and an auditor
        who can resolve it has exactly what they need.

        This is the honest-unknown rule applied to a person rather than to a language pack: state
        what is known, name what is missing, and invent nothing. The alternative considered and
        rejected was printing "a designer" — which would be prose standing where a specific human
        being is supposed to be, in the one annexure whose entire purpose is attribution.
        """
        name = (self.accepted_by or "").strip()
        if name:
            return name
        account = (self.accepted_by_id or "").strip()
        if account:
            return f"the account {account}, whose name this report could not resolve"
        # Neither, on an accepted layer, would mean the acceptance wrote no actor — which the
        # decision log makes impossible. Reachable only from a hand-built item, so it says so.
        return "somebody this report cannot identify"

    @property
    def has_text(self) -> bool:
        return bool(self.text and self.text.strip())

    @property
    def word_count(self) -> int:
        return len(plain_text(self.text).split())

    @property
    def model_name(self) -> str:
        """The model, as a reader can act on it, or the honest absence.

        THE MODEL ID IS THE WHOLE REASON THE PROVENANCE COLUMNS EXIST. Plan §3 rule 2: without it,
        a systematic error found in six months — a provider silently changing a checkpoint, a
        quantization that mangles Odia numerals — cannot be traced to the material it damaged, and
        the only remedy left is to distrust every transcript in the archive.
        """
        model = (self.model_id or "").strip()
        version = (self.model_version or "").strip()
        if not model or model == UNRECORDED:
            return "the model was not recorded"
        return f"{model} {version}".strip()

    @property
    def provider_name(self) -> str:
        provider = (self.provider or "").strip()
        if not provider or provider == UNRECORDED:
            return ""
        return provider


# --------------------------------------------------------------------------------------
# Carrying the layers to the renderer
# --------------------------------------------------------------------------------------

# The attribute the items travel on, set with ``setattr`` rather than declared on ``WorkshopData``.
# The reasoning is ``report_annexures``' and is repeated rather than referenced because it decides
# what happens when it fails: ``WorkshopData`` is a plain dataclass in a module owned by the report
# builder, and a feature that can be wired without editing a shared file should be.
_ATTR = "ai_layers"


def attach_ai_layers(data: Any, items: list[AiLayerItem] | tuple[AiLayerItem, ...]) -> Any:
    """Put the loaded layers on the workshop data the builder will walk. Returns ``data``."""
    try:
        setattr(data, _ATTR, tuple(items))
    except AttributeError:
        # A ``slots`` dataclass would refuse the attribute. Losing the annexure is the right
        # failure: a report missing an appendix is still a report, and raising here would take away
        # a designer's ability to generate anything at all over an optional extra.
        return data
    return data


def ai_layers_of(data: Any) -> tuple[AiLayerItem, ...]:
    """The layers attached to ``data``, or none. Safe on any workshop data object."""
    return tuple(getattr(data, _ATTR, ()) or ())


# --------------------------------------------------------------------------------------
# The blocks
# --------------------------------------------------------------------------------------


def layer_index_block(items: tuple[AiLayerItem, ...]) -> TableBlock:
    """The contents table at the head of the annexure.

    THE TIER COLUMN IS WHY THIS TABLE EXISTS AT ALL. A reader who wants to know how much of this
    document was machine-assisted, and by what, should be able to answer it from one table rather
    than by reading every heading in the annexure. Plan §2.1 makes that a requirement rather than a
    convenience: a fleet where a 4 GB handset and a 12 GB one run different tiers produces two
    classes of record, and without this column they are indistinguishable on the page.
    """
    return TableBlock(
        columns=(
            TableColumn("What it is", 26.0),
            TableColumn("Produced", 26.0),
            TableColumn("Model", 22.0),
            TableColumn("Accepted by", 16.0),
            TableColumn("Words", 10.0, numeric=True),
        ),
        rows=tuple(
            (
                runs_of(item.title),
                runs_of(tier_words(item.tier)),
                runs_of(item.model_name),
                runs_of(item.acceptor),
                runs_of(f"{item.word_count:,}"),
            )
            for item in items
        ),
        caption=(
            "Text in this report that was produced by a machine, and the person who accepted each "
            "passage. Every layer listed here was read against its source and accepted before it "
            "was included."
        ),
    )


def provenance_line(item: AiLayerItem) -> str:
    """The one line under each heading saying what made this text, from what, and who stood behind it.

    ALL FOUR FACTS OR AN HONEST GAP FOR EACH. A reader who will quote a passage has to be able to
    tell machine text from typed text, to find the material it was made from, and to know whose
    judgement admitted it. Where a fact was never recorded the line says so in words — the honest
    unknown that ``UNRECORDED`` exists to carry, rather than a blank that reads as "none".
    """
    parts: list[str] = [f"Produced {tier_words(item.tier)}"]
    provider = item.provider_name
    if provider:
        parts.append(f"provider {provider}")
    parts.append(item.model_name)
    if item.target_language.strip():
        # A TRANSLATION SAYS BOTH ENDS OR IT SAYS NOTHING USEFUL. "in English" beside a translated
        # passage tells a reader the one thing they could already see and withholds the one thing
        # they need — what it was translated FROM, which is what they would have to go back to in
        # order to check it. The source is stated even when it is UNRECORDED, because "translated
        # from a language nobody recorded" is a fact a reader can act on (find the recording) and a
        # blank is not.
        source = item.source_language.strip() or UNRECORDED
        source_words = (
            "a language nobody recorded"
            if source == UNRECORDED
            else "several languages, interleaved"
            if source.lower() == "multi"
            else source
        )
        parts.append(f"translated from {source_words} into {item.target_language.strip()}")
    elif item.language and item.language.strip():
        # ``multi`` is a legitimate value and not a placeholder: Deepgram Nova-3 is deliberately
        # called with ``language=multi`` because a workshop is Hindi code-switched with English
        # mid-sentence. Printing it verbatim would be a token; saying what it means is not.
        language = item.language.strip()
        parts.append(
            "several languages, interleaved" if language.lower() == "multi" else f"in {language}"
        )
    if item.produced_at:
        parts.append(item.produced_at[:10])
    if item.source_kind:
        origin = item.source_label or item.source_id or "an unnamed source"
        source_kind = item.source_kind.upper()
        if source_kind == "MEDIA":
            parts.append(f"made from the recording “{origin}”")
        elif source_kind == "SUPPLIED_TEXT":
            # THE NOTE ITSELF, QUOTED, because for this source kind there is no row a reader could
            # look up instead — the words exist only on this layer. Elided rather than printed whole:
            # the point of the line is to show WHAT was worked on, and a provenance line carrying ten
            # pages of note before it reaches "Accepted by" has stopped being a line.
            parts.append(f"made from the note “{_elide(origin)}”")
        else:
            parts.append(f"made from “{origin}”")
    accepted = (
        f"Accepted by {item.acceptor}"
    ) + (f" on {item.accepted_at[:10]}" if item.accepted_at else "")
    return " · ".join(parts) + ". " + accepted + "."


#: How much of a supplied note the provenance line quotes before eliding.
#:
#: NOT ``PREVIEW_CHARS``, though it is nearly the same number, and the difference is deliberate:
#: that one titles a layer in a LIST a designer scans, and this one quotes the evidence inside a
#: sentence in a document. They are free to diverge — a page can carry more than a handset row — so
#: they are two constants with two reasons rather than one with a footnote, exactly as
#: ``MAX_PARAGRAPHS_PER_LAYER`` is to ``report_annexures``' own ceiling.
SOURCE_QUOTE_CHARS = 240


def _elide(text: str) -> str:
    """One line of a supplied note, at most :data:`SOURCE_QUOTE_CHARS` of it.

    Newlines are collapsed for ``ai_layers._preview``'s reason: a note typed into a textarea carries
    its own line breaks, and a quotation that kept them would break the provenance sentence across
    the page.
    """
    flat = " ".join((text or "").split())
    if len(flat) <= SOURCE_QUOTE_CHARS:
        return flat
    return flat[:SOURCE_QUOTE_CHARS].rstrip() + "…"


def speaker_labels_are_guessed(item: AiLayerItem) -> bool:
    """Whether this layer prints speaker turns that a diarizer, rather than a person, decided.

    Both halves are required and neither is sufficient. A layer with no turns has nothing to caution;
    a layer with turns that stands on a designer's own typing has turns the designer wrote. See
    :data:`SPEAKER_NOTE` for what this deliberately does not catch.
    """
    if str(item.source_kind or "").upper() != "MEDIA":
        return False
    if item.text_withheld or not item.text:
        return False
    return bool(_SPEAKER_TURN.search(item.text))


def layer_body_blocks(item: AiLayerItem) -> list[Block]:
    """One layer's text as report blocks.

    Reuses ``report_annexures.transcript_body_blocks`` rather than re-parsing, because the stored
    form is the same stored form: ``services/ai.py`` and ``services/transcript_format.py`` fix a
    speaker turn as a bold span ending in a colon, and a layer derived from a transcript keeps it.
    Two parsers for one format is two chances to render ``**Speaker 1:**`` verbatim into a document
    submitted to a ministry.
    """
    blocks = transcript_body_blocks(item.text)
    if len(blocks) > MAX_PARAGRAPHS_PER_LAYER:
        kept = blocks[:MAX_PARAGRAPHS_PER_LAYER]
        kept.append(ParagraphBlock(
            runs=runs_of(
                f"[Text truncated after {MAX_PARAGRAPHS_PER_LAYER} paragraphs. The full layer is "
                f"held against the workshop in the repository, under {item.layer_id}.]"
            ),
            style=ParaStyle.NOTE,
        ))
        return kept
    return blocks


#: The sentence that opens the annexure. It is the plainest statement of rule 4 in the whole system
#: and it is addressed to the officer reading the document, not to the designer who made it.
#:
#: IT MAKES A CLAIM ABOUT THIS ANNEXURE AND NOT ABOUT THE WHOLE DOCUMENT, and the second half of that
#: sentence used to overreach in both places. The lead promised "the named person" where the loader
#: could only supply an account id (see :attr:`AiLayerItem.acceptor`), and the index caption asserted
#: that "nothing a machine produced appears anywhere in this document without being listed here" —
#: which the same document falsifies whenever the transcript annexure is also switched on.
#:
#: WHY THAT WAS FALSE, and it is worth stating because it is not obvious: the transcript annexure
#: prints ``MediaFile.transcriptText`` in full, and on a deployment left at the default
#: ``REFINED_TRANSLATED`` that column is not the provider's own words at all — it is an OpenAI rewrite
#: translated into English, with the provider's words kept in ``transcriptSummary``. This module's own
#: ``ai_layers.transcript_rungs`` docstring says so, in this vocabulary: model prose, possibly in a
#: language the artisan never spoke. None of it is listed here, none of it carries a tier or a model
#: id, and nobody accepted it — so the strongest true claim is about the passages BELOW, and that is
#: now all either sentence makes.
LEAD = (
    "The passages below were produced by automatic transcription, recognition or summarisation, and "
    "each was read against its source and accepted by the person recorded beside it before it was "
    "included. They are printed separately, and named as machine-assisted, so that nothing in the "
    "body of this report is mistaken for a machine's words and nothing a machine wrote is mistaken "
    "for the author's."
)


#: Printed under every EXPANDED heading, and under no other kind's.
#:
#: **THE ONE VERB IN THIS SYSTEM THAT INVENTS SENTENCES GETS ITS OWN SENTENCE ON THE PAGE.** Every
#: other kind transforms words somebody said: a transcript, a correction, a translation, a caption of
#: a photograph that exists. An expansion starts from a designer's shorthand — "dabu paste — gum,
#: clay, 3 days" — and produces paragraphs, and the paragraphs contain claims nobody made. The
#: annexure's general lead says the passages below were machine-produced and accepted; that is true
#: of all of them and is not enough here, because a reader who knows a machine WROTE a passage still
#: assumes the facts in it came from somewhere.
#:
#: IT IS ADDRESSED TO THE OFFICER AND NOT TO THE DESIGNER, like ``LEAD``: it says what to do with the
#: paragraph, which is to treat the designer's note as the record and this as a reading of it.
#:
#: AND IT IS PRINTED EVEN THOUGH THE SOURCE NOTE IS PRINTED IN THE PROVENANCE LINE ABOVE IT. The
#: "made from" clause names the note; it does not say that everything beyond the note is a machine's
#: invention, and a reader comparing a four-line note with three paragraphs of prose should not have
#: to work that out for themselves.
EXPANDED_NOTE = (
    "This passage was written by a machine from a short note the designer made. The note itself is "
    "quoted above as the source. Anything in this passage that is not in that note — a detail, a "
    "reason, a connection between two things — was supplied by the model and was not recorded in the "
    "field. Treat the note as the record and this as a reading of it, and check any specific claim "
    "against the workshop's own material before quoting it."
)

#: Recognises the one speaker shape this pipeline produces: a bold span ending in a colon.
#:
#: The SAME expression ``transcript_format`` parses with, quoted rather than imported for that
#: module's own stated reason about having two parsers for one format — except that here the risk runs
#: the other way. If this pattern and that one ever disagreed, the failure would be a page that RENDERS
#: speaker turns and does not CAUTION them, which is the exact defect below. Kept adjacent and named
#: so a change to one is a change somebody has to make to both.
_SPEAKER_TURN = re.compile(r"\*\*[^*\n]{1,60}?:\*\*")

#: Printed under any layer made FROM A RECORDING whose text carries speaker turns.
#:
#: **A SPEAKER LABEL IS A MODEL'S GUESS AT WHO SPOKE, AND THE PAGE SAID IT LIKE A FACT.** Nobody in
#: the courtyard told this system how many people were in the room or which of them was talking:
#: ``ai._diarized_markdown`` numbers whatever the provider separated, and the provider inferred it
#: from the audio alone. Scribe will separate up to 32 voices; both engines merge two quiet speakers,
#: split one person who moved away from the microphone, and give an interjection to whoever was
#: louder. What the officer sees is ``Artisan:`` and ``Interviewer:`` down a page in a sanctioned
#: report, which reads as a record of who said what — and a line attributed to the wrong voice in this
#: archive means an interviewer's words printed as an artisan's, under a named person's acceptance.
#:
#: **THE ANNEXURE'S GENERAL LEAD IS NOT ENOUGH HERE, FOR ``EXPANDED_NOTE``'s REASON EXACTLY.** It says
#: the passages were machine-produced and accepted, which is true of all of them; a reader who knows a
#: machine TRANSCRIBED a passage still assumes the labels in it distinguish real people, in the same
#: way that a reader who knows a machine WROTE one still assumes its facts came from somewhere.
#:
#: **WHY ONLY MEDIA-ROOTED LAYERS, WHICH IS NARROWER THAN THE PROBLEM.** A layer standing on supplied
#: text may carry turns a DESIGNER typed — "**Rita:** …" in their own note — and telling an officer
#: those were guessed by a model would be a false statement made in the name of honesty. Source kind is
#: the only evidence this item carries about that, and MEDIA is the one value that settles it. **What
#: this therefore misses, stated rather than left to be discovered: a translation or a proofread OF a
#: diarized transcript is LAYER-rooted**, so its labels came from diarization and carry no caution
#: here. Fixing that means the item knowing its chain root, which the loader resolves for the media
#: gate and does not carry onto the page.
SPEAKER_NOTE = (
    "The speaker labels in this passage — who is saying which line — were decided by the machine that "
    "transcribed the recording, not by anybody who was present. Nothing recorded how many people were "
    "speaking or who they were: the labels are that machine's separation of the voices it heard, and "
    "it can merge two speakers into one or split one across two. The numbering is by order of first "
    "speaking and is not a name. Check a line against the recording before attributing it to a "
    "particular person."
)

#: What stands where a withheld layer's text would have been.
#:
#: IT NAMES A PERSON'S DECISION, NOT A SYSTEM STATE. "Access denied" would tell a ministry officer
#: nothing they can act on and would read as a malfunction. What actually happened is that the account
#: which generated this file may open the workshop but may not read that particular recording — a
#: distinction this repository draws per media file precisely so that one designer's interviews are
#: not readable by every colleague on the workshop — and the way to a complete copy is a person, not a
#: retry.
WITHHELD_NOTE = (
    "The text of this passage is not printed in this copy. It was produced from a recording that the "
    "account which generated this report may not read: on this system, permission to open a workshop "
    "and permission to read a particular recording are granted separately. What produced the passage, "
    "and who accepted it, are stated above and are complete. For a copy carrying the text, ask the "
    "colleague who uploaded the recording to generate the report, or to grant access to their media."
)


def append_ai_layer_annexure(
    doc: DocumentBuilder,
    items: tuple[AiLayerItem, ...] | list[AiLayerItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> int:
    """Append the whole annexure to ``doc``. Returns how many layers were printed.

    THE ONE CALL SITE is ``report_builder.ReportBuilder.build``, in the ``if/elif`` chain over
    ``section.special``, beside the ``ANNEXURE_TRANSCRIPTS`` branch. Keep it the only one, for the
    reason that module states: two callers would be two chances to pass a different heading, and a
    report whose annexure is numbered on the phone and unnumbered at the office is exactly the
    divergence the report port exists to end.

    **UNACCEPTED LAYERS ARE REFUSED HERE, not merely absent from the caller's query.** This is rule 3
    of the layering law standing at the door of the document itself. The caller loads through
    ``ai_layers.accepted_layers``, which is the single definition of accepted; this second check
    exists because that loader can be changed by somebody who has not read this file, and the cost of
    getting it wrong is model prose entering a sanctioned report with nobody's name against it.

    With no layers attached — nothing accepted, or the designer did not ask — this appends nothing at
    all, not even the page break, so a template carrying the section is byte-for-byte the template it
    was before whenever nobody asked.
    """
    # A WITHHELD LAYER IS LISTED AND NOT PRINTED, which is why this is two conditions rather than one.
    #
    # `has_text` is false for a withheld layer, because the loader blanks the text it may not show. So
    # the obvious single filter — accepted AND has_text — would drop the row ENTIRELY, and with it the
    # tier, the model and the acceptor, none of which is the recording's content and all of which is
    # what a reviewer opens this annexure to see. Worse, a reader would have no way to tell a layer
    # withheld from a layer that was never made.
    printed = [item for item in items if item.accepted and (item.has_text or item.text_withheld)]
    if not printed:
        return 0
    if page_break_before:
        doc.add(PageBreakBlock())
    doc.heading(heading or DEFAULT_HEADING, 1, numbered=numbered)
    doc.para(LEAD, style=ParaStyle.LEAD)
    doc.add(layer_index_block(tuple(printed)))
    for item in printed:
        doc.heading(item.title, 2, numbered=numbered)
        doc.para(provenance_line(item), style=ParaStyle.NOTE)
        if str(item.kind or "").upper() == "EXPANDED":
            # Above the passage rather than below it, deliberately: a caution that follows three
            # paragraphs of confident prose is read after the reader has already believed them.
            doc.para(EXPANDED_NOTE, style=ParaStyle.NOTE)
        if speaker_labels_are_guessed(item):
            # Above the passage, for EXPANDED_NOTE's reason: a caution about who is speaking, printed
            # after two pages of dialogue, arrives once the reader has already assigned the lines.
            doc.para(SPEAKER_NOTE, style=ParaStyle.NOTE)
        if item.text_withheld:
            # SAID IN THE DOCUMENT, not left as a gap under a heading. The reader is holding a report
            # whose annexure names a passage it does not contain, and the only thing worse than that
            # is a heading with nothing under it and no explanation.
            doc.para(WITHHELD_NOTE, style=ParaStyle.NOTE)
            continue
        for block in layer_body_blocks(item):
            doc.add(block)
    return len(printed)


def ai_layer_annexure_blocks(
    items: tuple[AiLayerItem, ...] | list[AiLayerItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> tuple[Block, ...]:
    """The annexure as a plain block tuple, built in isolation — for tests and for a preview.

    The heading numbers restart at 1 because nothing else has been counted, which is why the real
    report goes through :func:`append_ai_layer_annexure` instead.
    """
    scratch = DocumentBuilder(meta=ReportMeta(title=""))
    append_ai_layer_annexure(
        scratch, items, heading=heading, numbered=numbered, page_break_before=page_break_before
    )
    return scratch.build().blocks


def annexure_warnings(items: tuple[AiLayerItem, ...] | list[AiLayerItem]) -> list[str]:
    """What the designer must be told about what did NOT print. These reach ``X-Report-Warnings``.

    A LAYER LEFT OUT FOR WANT OF AN ACCEPTANCE IS THE ONE OMISSION THAT LOOKS LIKE A BUG. The
    designer turned the annexure on, knows a summary exists because they read it on the acceptance
    screen, and finds it missing from a sixty-page document. Silence there teaches them that the
    toggle does not work; naming it teaches them what acceptance is for, which is the one thing this
    whole feature needs them to understand.
    """
    warnings: list[str] = []
    unaccepted = [item for item in items if not item.accepted]
    if unaccepted:
        warnings.append(
            f"{len(unaccepted)} machine-generated layer(s) were left out of the annexure because "
            "nobody has accepted them yet. Open the workshop's AI layers, read each one against its "
            "source, and accept the ones this report should carry."
        )
    empty = [item for item in items if item.accepted and not item.has_text]
    if empty:
        warnings.append(
            f"{len(empty)} accepted layer(s) carry no text and were not printed — they hold "
            "structured data (tags or extracted details) rather than prose."
        )
    # THE ONE WARNING THAT FIRES ON A SUCCESSFUL RENDER, and it is not an error. Every other warning
    # here says something was left out; this says something went IN, and says it because the designer
    # is the last person who can take it out again. They accepted the expansion on a screen, possibly
    # weeks ago; the document going to the directorate is where it stops being a suggestion.
    expanded = [
        item
        for item in items
        if item.accepted and item.has_text and str(item.kind or "").upper() == "EXPANDED"
    ]
    if expanded:
        warnings.append(
            f"{len(expanded)} passage(s) in the annexure were WRITTEN by a model from your notes "
            "rather than transcribed or corrected — they contain sentences nobody said. Each is "
            "printed with the note it was made from and a caution beside it. Read them once more "
            "before this file leaves your hands; withdrawing an acceptance takes the passage out of "
            "the next copy."
        )
    return warnings


def runs_for_heading(item: AiLayerItem) -> tuple[Run, ...]:
    """The heading as runs, for a caller assembling blocks by hand. Kept beside the wording it uses."""
    return tuple(runs_of(item.title, bold=True)) + tuple(
        runs_of(f"  ({tier_words(item.tier)})", italic=True)
    )


__all__ = [
    "DEFAULT_HEADING",
    "EXPANDED_NOTE",
    "LEAD",
    "MAX_PARAGRAPHS_PER_LAYER",
    "SPEAKER_NOTE",
    "UNRECORDED",
    "AiLayerItem",
    "ai_layer_annexure_blocks",
    "ai_layers_of",
    "annexure_warnings",
    "append_ai_layer_annexure",
    "attach_ai_layers",
    "kind_title",
    "layer_body_blocks",
    "layer_index_block",
    "provenance_line",
    "runs_for_heading",
    "speaker_labels_are_guessed",
    "tier_words",
]
