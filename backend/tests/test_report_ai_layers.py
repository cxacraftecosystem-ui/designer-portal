"""The AI annexure: that it prints only what a person accepted, and always says a machine made it.

WHAT IS ACTUALLY AT RISK HERE, and it is not layout. This is the only path in the system by which
model prose reaches a document a ministry officer reads. Two things must be true of every such
document, and both are asserted below against the rendered blocks rather than against an intention:

  1. **Nothing unaccepted prints.** Plan §3 rule 3 — a layer is inert until a person accepts it —
     and the report is where "inert" either means something or does not.
  2. **Everything that prints says it was machine-made, in the heading**, not only in a note
     underneath. A heading survives skimming, quoting and pasting into a covering note; a caption
     does not.

The third thing, which is what makes a defect found in six months survivable: every printed layer
names the model that produced it, or says in words that nobody recorded one.
"""

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_ai_layers import (
    AiLayerItem,
    ai_layer_annexure_blocks,
    ai_layers_of,
    annexure_warnings,
    attach_ai_layers,
    kind_title,
    provenance_line,
    tier_words,
)
from app.services.report_builder import WorkshopData, build_report
from app.services.report_model import ImageRef, ParagraphBlock, ReportMeta, TableBlock, runs_text
from app.services.report_templates import TEMPLATES, SpecialSection, apply_report_settings


def _layer(**kw) -> AiLayerItem:
    base = {
        "layer_id": "layer_1",
        "kind": "CLEANED_TRANSCRIPT",
        "tier": "TIER_3",
        "provider": "deepgram",
        "model_id": "nova-3",
        "language": "multi",
        "produced_at": "2026-03-04T10:00:00Z",
        "accepted": True,
        "accepted_at": "2026-08-12T09:00:00Z",
        # A NAME **AND** THE ID BESIDE IT, because production supplies the id and may supply no name.
        # This fixture used to carry only `accepted_by="A. Designer"` — a value no production caller
        # can produce — so every assertion below passed while the only code path that fills the field
        # passed a cuid. See the note on `AiLayerItem.accepted_by`.
        "accepted_by": "A. Designer",
        "accepted_by_id": "cmld8x0a10000gzsy4t9v2b1q",
        "source_kind": "MEDIA",
        "source_id": "media_7",
        "source_label": "Artisan's spoken explanation",
        "text": "**Interviewer:** How is the dabu paste made?\n\n**Artisan:** With mud and gum arabic.",
    }
    base.update(kw)
    return AiLayerItem(**base)


def _text_of(blocks) -> str:
    """Every word the annexure would put on a page, flattened. Tables included."""
    out: list[str] = []
    for block in blocks:
        if isinstance(block, ParagraphBlock):
            out.append(runs_text(block.runs))
        elif isinstance(block, TableBlock):
            out.extend(runs_text(cell) for row in block.rows for cell in row)
            if block.caption:
                out.append(block.caption)
        else:
            out.append(getattr(block, "text", "") or "")
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# Rule 3 at the door of the document
# --------------------------------------------------------------------------------------


def test_an_unaccepted_layer_prints_nothing_at_all():
    """Not a heading, not a page break, not an empty index row.

    An unaccepted layer is a suggestion sitting in a table. If it reached the annexure with so much
    as a heading, a reader would find a machine's paragraph in a sanctioned report with nobody's
    name against it, which is the single outcome the layering law exists to prevent.
    """
    assert ai_layer_annexure_blocks([_layer(accepted=False)]) == ()


def test_an_accepted_layer_with_no_text_prints_nothing_but_is_reported():
    """TAGS and METADATA carry structure, not prose. Silence about them would look like a bug."""
    tags = _layer(kind="TAGS", text="")
    assert ai_layer_annexure_blocks([tags]) == ()
    assert any("carry no text" in w for w in annexure_warnings([tags]))


def test_the_designer_is_told_what_acceptance_left_out():
    """They turned the annexure on, read the summary on the acceptance screen, and it is missing.

    Silence there teaches a designer the toggle does not work. Naming it teaches them what
    acceptance is for, which is the one thing this feature needs them to understand.
    """
    warnings = annexure_warnings([_layer(), _layer(layer_id="l2", accepted=False)])
    assert any("nobody has accepted them yet" in w for w in warnings)
    assert any("accept the ones this report should carry" in w for w in warnings)


def test_a_fully_accepted_set_raises_no_warning():
    assert annexure_warnings([_layer(), _layer(layer_id="l2")]) == []


# --------------------------------------------------------------------------------------
# The verbs on the page: the one that invents, and the one with two languages
# --------------------------------------------------------------------------------------


def test_an_expansion_carries_a_caution_above_the_prose_and_no_other_kind_does():
    """**THE ONE VERB THAT INVENTS SENTENCES GETS ITS OWN SENTENCE ON THE PAGE.**

    Every other kind transforms words somebody said. An expansion starts from a designer's shorthand
    and produces paragraphs, and the paragraphs contain claims nobody made. The annexure's general
    lead says the passages were machine-produced and accepted — true of all of them, and not enough
    here, because a reader who knows a machine WROTE a passage still assumes the facts in it came
    from somewhere.

    ABOVE the prose, deliberately: a caution that follows three confident paragraphs is read after
    the reader has already believed them.
    """
    from app.services.report_ai_layers import EXPANDED_NOTE

    expanded = _text_of(ai_layer_annexure_blocks([
        _layer(kind="EXPANDED", source_kind="SUPPLIED_TEXT", source_id="",
               source_label="dabu — gum, clay, 3 days",
               text="The dabu paste is prepared from gum and clay over three days."),
    ]))
    assert EXPANDED_NOTE in expanded
    assert expanded.index(EXPANDED_NOTE) < expanded.index("The dabu paste is prepared")

    ordinary = _text_of(ai_layer_annexure_blocks([_layer()]))
    assert EXPANDED_NOTE not in ordinary


def test_a_transcript_from_a_recording_says_its_speaker_labels_were_guessed():
    """**A SPEAKER LABEL IS A MODEL'S GUESS AT WHO SPOKE, AND THE PAGE SAID IT LIKE A FACT.**

    Nobody in the courtyard told this system how many people were in the room. The provider inferred
    it from the audio, ``ai._diarized_markdown`` numbered whatever it separated, and the officer sees
    ``Artisan:`` and ``Interviewer:`` down a page of a sanctioned report — which reads as a record of
    who said what. A line attributed to the wrong voice here is an interviewer's words printed as an
    artisan's, under a named person's acceptance.

    The annexure's general LEAD is not enough, for EXPANDED_NOTE's reason exactly: a reader who knows
    a machine transcribed a passage still assumes the labels in it distinguish real people.

    ABOVE the passage, because a caution about who is speaking printed after two pages of dialogue
    arrives once the reader has already assigned the lines.
    """
    from app.services.report_ai_layers import SPEAKER_NOTE

    text = _text_of(ai_layer_annexure_blocks([_layer()]))
    assert SPEAKER_NOTE in text
    assert text.index(SPEAKER_NOTE) < text.index("How is the dabu paste made?")


def test_a_layer_with_no_speaker_turns_carries_no_caution_about_them():
    """A caution printed under a passage with no labels in it is noise, and noise is how a reader
    learns to skip the notes — including the one that matters on the next page."""
    from app.services.report_ai_layers import SPEAKER_NOTE

    text = _text_of(ai_layer_annexure_blocks([_layer(text="The dabu paste is mixed with gum.")]))
    assert SPEAKER_NOTE not in text


def test_turns_a_designer_typed_are_never_described_as_a_machines_guess():
    """**THE HALF OF THIS THAT WOULD BE A FALSE STATEMENT MADE IN THE NAME OF HONESTY.** A layer
    standing on supplied text carries turns the DESIGNER wrote — "**Rita:** …" in their own note —
    and telling an officer those were decided by a machine is exactly as wrong as the silence it
    replaces, in the other direction. Source kind is the only evidence the item carries about that,
    and MEDIA is the one value that settles it."""
    from app.services.report_ai_layers import SPEAKER_NOTE, speaker_labels_are_guessed

    typed = _layer(
        kind="PROOFREAD", source_kind="SUPPLIED_TEXT", source_id="",
        text="**Rita:** The vat is set at dawn.\n\n**Devi:** With indigo.",
    )
    assert speaker_labels_are_guessed(typed) is False
    assert SPEAKER_NOTE not in _text_of(ai_layer_annexure_blocks([typed]))


def test_a_withheld_passage_is_not_cautioned_about_labels_it_does_not_print():
    """The text is not in this copy, so there are no labels on the page to be wrong about. The
    provenance still prints, which is the whole point of withholding rather than omitting.

    **TWO CASES, BECAUSE ONE OF THEM PINNED NOTHING.** This test used to pass ``text=""`` alone, which
    is what the loader produces today — and ``speaker_labels_are_guessed`` answers that on its
    ``not item.text`` clause and never reaches the withheld one. Deleting ``item.text_withheld or``
    from the guard left this green, so the clause that actually decides the withheld case was
    unasserted. The second case below is the one that holds it: text present, and NOT PRINTED, because
    ``append_ai_layer_annexure`` substitutes ``WITHHELD_NOTE`` for the body. A caution about labels
    would then be a caution about labels nobody reading this copy can see, on the one page where the
    reader has already been told the passage is missing.
    """
    from app.services.report_ai_layers import (
        SPEAKER_NOTE,
        WITHHELD_NOTE,
        speaker_labels_are_guessed,
    )

    assert speaker_labels_are_guessed(_layer(text="", text_withheld=True)) is False

    # The loader blanks what it may not show, so this shape is defensive — which is exactly why it
    # needs an assertion rather than a comment: the guard has a clause for it.
    still_carrying_turns = _layer(text_withheld=True)
    assert "**Artisan:**" in still_carrying_turns.text, "the fixture must actually carry turns"
    assert speaker_labels_are_guessed(still_carrying_turns) is False
    page = _text_of(ai_layer_annexure_blocks([still_carrying_turns]))
    assert WITHHELD_NOTE in page
    assert SPEAKER_NOTE not in page
    assert "How is the dabu paste made?" not in page


def test_the_designer_is_warned_when_invented_prose_is_going_into_the_file():
    """The one warning that fires on a SUCCESSFUL render, and it is not an error.

    Every other warning says something was left out; this says something went IN. The designer
    accepted the expansion on a screen, possibly weeks ago, and the document leaving their hands is
    where it stops being a suggestion.
    """
    warnings = annexure_warnings([_layer(kind="EXPANDED")])
    assert any("WRITTEN by a model" in w for w in warnings)
    assert any("withdrawing an acceptance" in w.lower() for w in warnings)


def test_a_translation_prints_both_languages_and_never_only_the_one_it_is_in():
    """"In English" beside a translated passage tells a reader the one thing they could already see
    and withholds the one thing they need — what it was translated FROM, which is what they would
    have to go back to in order to check it."""
    text = _text_of(ai_layer_annexure_blocks([
        _layer(kind="TRANSLATION", language="en", source_language="or", target_language="en"),
    ]))
    assert "translated from or into en" in text


def test_a_translation_whose_source_language_nobody_recorded_says_so_in_words():
    """A fact nobody wrote down is stated as unknown, never guessed and never left as a blank that
    reads as "none" — and "translated from a language nobody recorded" is something a reader can act
    on, where a blank is not."""
    text = _text_of(ai_layer_annexure_blocks([
        _layer(kind="TRANSLATION", language="en", source_language="UNRECORDED",
               target_language="en"),
    ]))
    assert "a language nobody recorded" in text


def test_a_translation_from_code_switched_speech_is_explained_rather_than_printed_as_a_token():
    text = _text_of(ai_layer_annexure_blocks([
        _layer(kind="TRANSLATION", language="en", source_language="multi", target_language="en"),
    ]))
    assert "several languages, interleaved" in text


def test_a_supplied_note_is_quoted_as_the_source_and_elided_rather_than_printed_whole():
    """For a supplied-text source there is no row a reader could look up instead — the words exist
    only on the layer. A provenance line carrying ten pages of note before it reaches "Accepted by"
    has stopped being a line."""
    text = _text_of(ai_layer_annexure_blocks([
        _layer(kind="PROOFREAD", source_kind="SUPPLIED_TEXT", source_id="",
               source_label="dabu " * 300),
    ]))
    assert "made from the note" in text
    assert "…" in text


# --------------------------------------------------------------------------------------
# Rule 4: it says a machine made it, in the heading
# --------------------------------------------------------------------------------------


def test_every_kind_is_titled_as_machine_work():
    """Including a kind this build has never heard of.

    A newer server can write a kind out of a Postgres enum this deployment predates. The failure
    that must not happen is model prose appearing under a heading that does not say so, so the
    fallback names it too rather than printing the raw token.
    """
    for kind in ("RAW_TRANSCRIPT", "CLEANED_TRANSCRIPT", "SUMMARY", "OCR_TEXT",
                 "STRUCTURED_TEXT", "TAGS", "METADATA",
                 "PROOFREAD", "EXPANDED", "TRANSLATION", "CAPTION", "SUBTITLES",
                 "A_KIND_FROM_THE_FUTURE", ""):
        title = kind_title(kind).lower()
        assert any(word in title for word in ("ai", "automatic", "machine")), kind


def test_the_heading_of_a_printed_layer_says_it_is_machine_made():
    text = _text_of(ai_layer_annexure_blocks([_layer()]))
    assert "AI-cleaned transcript" in text


def test_the_tier_is_printed_in_words_and_never_as_a_bare_number():
    """Plan §2.1: a cloud-diarized interview and a device-guessed one must not look alike.

    An officer has no reason to know what "tier 2" means, and would not guess that tier 1 is the
    one that works without a connection rather than the weakest one.
    """
    for tier, expected in (("TIER_1", "handset"), ("TIER_2", "handset"), ("TIER_3", "server")):
        assert expected in tier_words(tier)
        assert "Tier" not in tier_words(tier)
    assert "cannot identify" in tier_words("TIER_9")


def test_the_index_table_names_the_tier_and_the_acceptor_for_every_row():
    text = _text_of(ai_layer_annexure_blocks([_layer()]))
    assert "on the server, by a hosted model" in text
    assert "A. Designer" in text


# --------------------------------------------------------------------------------------
# Rule 2: the provenance, including the honest absence of it
# --------------------------------------------------------------------------------------


def test_an_unrecorded_model_is_said_in_words_not_left_blank():
    """``UNRECORDED`` is a real stored value, not a placeholder this module invented.

    ``media_queue`` has never persisted which of the four providers produced a transcript, so it is
    the common case on day one. Printing the token into a ministry document would read as a broken
    export; printing nothing would read as "there was no model".
    """
    line = provenance_line(_layer(provider="UNRECORDED", model_id="UNRECORDED"))
    assert "the model was not recorded" in line
    assert "UNRECORDED" not in line


def test_a_recorded_model_and_version_both_reach_the_page():
    line = provenance_line(_layer(model_id="nova-3", model_version="2026-02"))
    assert "nova-3 2026-02" in line


def test_multi_is_explained_rather_than_printed_as_a_token():
    """Deepgram Nova-3 is called with ``language=multi`` because a workshop is code-switched."""
    assert "several languages, interleaved" in provenance_line(_layer(language="multi"))
    assert "in Odia" in provenance_line(_layer(language="Odia"))


def test_the_provenance_names_who_accepted_it_and_when():
    line = provenance_line(_layer())
    assert "Accepted by A. Designer on 2026-08-12" in line


def test_an_unresolved_acceptor_is_printed_as_an_account_and_never_as_a_bare_id():
    """The production case until the loader resolves a name, and it must not read as a broken export.

    `attach_report_ai_layers` fills `accepted_by` from `DwAiLayer.acceptedById` — a cuid — because
    the name is not on the layer row. So a .docx reaching a directorate carried "Accepted by
    cmld8x0a10000gzsy4t9v2b1q" beneath a lead paragraph promising a named person, and the officer
    could not answer "whose judgement admitted this paragraph" without database access.

    A cuid is a perfectly good audit reference; what was missing was the clause saying that is what
    it is. Both halves are asserted here: the id survives, so an auditor can still resolve it, and it
    is labelled, so a reader is not left thinking the export failed.
    """
    line = provenance_line(_layer(accepted_by="", accepted_by_id="cmld8x0a10000gzsy4t9v2b1q"))
    assert "cmld8x0a10000gzsy4t9v2b1q" in line
    assert "the account" in line
    # The bare form is what this test exists to forbid.
    assert "Accepted by cmld8x0a10000gzsy4t9v2b1q" not in line


def test_a_withheld_layer_keeps_its_provenance_and_loses_only_the_recordings_content():
    """THE DISCLOSURE DEFECT, PINNED. A layer's text is a copy of a transcript, and a transcript is
    the content of a recording — gated per FILE, which is a different question from "may this account
    open the workshop". A `DesignWorkshopViewer` grant carries read and stage writes and says nothing
    about media, so a colleague could generate a report and receive, in a .docx they keep, the
    complete transcripts that the AI-layers screen and `GET /transcripts` both refuse them.

    Withholding is not dropping, and that is the half worth pinning: the tier, the model and the
    acceptor are not the recording's content, they are what a reviewer opens this annexure for, and a
    layer silently absent is indistinguishable from a layer nobody ever made.
    """
    withheld = _layer(text="", text_withheld=True)
    blocks = ai_layer_annexure_blocks([withheld])
    text = _text_of(blocks)
    # It is LISTED — heading, provenance, index row — so the reader knows the passage exists.
    assert "AI-cleaned transcript" in text
    assert "on the server, by a hosted model" in text
    assert "A. Designer" in text
    # And the reason is stated in the document rather than left as a gap under a heading.
    assert "may not read" in text
    # The artisan's words are NOT in it. This is the assertion the defect would fail.
    assert "dabu" not in text
    assert "gum arabic" not in text


def test_a_withheld_layer_is_still_dropped_when_nobody_accepted_it():
    """Withholding relaxes the text requirement, not the acceptance one.

    The filter is `accepted and (has_text or text_withheld)`, and the first conjunct is rule 3. A
    withheld, unaccepted layer must print nothing at all — otherwise the relaxation that lets
    provenance survive a media refusal would also let an unsigned layer onto the page.
    """
    assert ai_layer_annexure_blocks([_layer(text="", text_withheld=True, accepted=False)]) == ()


def test_no_wording_in_the_annexure_claims_the_whole_document_is_listed_here():
    """The index caption asserted "nothing a machine produced appears anywhere in this document
    without being listed here", and the same document falsifies it whenever the transcript annexure
    is on: `MediaFile.transcriptText` on a default REFINED_TRANSLATED deployment is an AI rewrite
    translated into English, printed in full, listed nowhere here, attributed to nobody and accepted
    by nobody. An absolute claim about a document this module does not control cannot be kept."""
    text = _text_of(ai_layer_annexure_blocks([_layer()]))
    assert "anywhere in this document" not in text
    assert "Nothing a machine produced" not in text
    # And what it says instead is still a real promise, about the passages it does control.
    assert "read against its source and accepted" in text


def test_the_source_is_named_so_a_reader_can_trace_it_back():
    assert "Artisan's spoken explanation" in provenance_line(_layer())
    # With no label resolved, the id is printed rather than nothing — a reader tracing a summary
    # back to its recording needs something to trace it with.
    assert "media_7" in provenance_line(_layer(source_label=""))


# --------------------------------------------------------------------------------------
# The template splice, and the pin it must not disturb
# --------------------------------------------------------------------------------------


def test_no_template_carries_the_section_by_default():
    """`report_templates_pin.json` is a by-value diff regenerated only inside the API container.

    Declaring the section in TEMPLATES would move that 485 KB fixture, and a checkout that cannot
    reach Docker cannot move it — so the feature would have to ship with a red test or with the pin
    hand-edited, which its own docstring names as the one thing that must never happen.
    """
    for template in TEMPLATES:
        assert SpecialSection.ANNEXURE_AI_LAYERS not in [s.special for s in template.sections]


def test_the_template_is_returned_unchanged_when_nobody_asks():
    """Identity, not equality: this is exactly what keeps every pinned case byte-identical."""
    template = TEMPLATES[0]
    assert apply_report_settings(template, None) is template
    assert apply_report_settings(template, None, include_ai_layers=None) is template
    assert apply_report_settings(template, None, include_ai_layers=False) is template


def test_asking_for_it_splices_exactly_one_section():
    sections = apply_report_settings(TEMPLATES[0], None, include_ai_layers=True).sections
    specials = [s.special for s in sections]
    assert specials.count(SpecialSection.ANNEXURE_AI_LAYERS) == 1


def test_the_annexure_never_interrupts_the_narrative():
    """It goes after every stage section, and before the completeness statement where there is one.

    An annexure spliced into the middle of the stages would be read as part of them, which is
    precisely what naming the text is meant to prevent.
    """
    for template in TEMPLATES:
        sections = apply_report_settings(template, None, include_ai_layers=True).sections
        specials = [s.special for s in sections]
        at = specials.index(SpecialSection.ANNEXURE_AI_LAYERS)
        last_stage = max(
            (i for i, s in enumerate(sections) if s.stage_key), default=-1
        )
        assert at > last_stage, template.id
        if SpecialSection.COMPLETENESS in specials:
            assert at < specials.index(SpecialSection.COMPLETENESS), template.id


# --------------------------------------------------------------------------------------
# End to end, through the real builder
# --------------------------------------------------------------------------------------


def _data() -> WorkshopData:
    """The same minimal shape ``test_design_workshops`` uses, so the two cannot drift."""
    return WorkshopData(workshop_id="w1", title="Workshop")


def _resolver(media_id: str):
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/png")


def _meta() -> ReportMeta:
    return ReportMeta(title="Design & Prototype Workshop")


def test_a_report_asked_for_the_annexure_carries_the_accepted_layer():
    """The whole chain: splice, attach, branch, render. A module with no call site is not a feature.

    This is the assertion that would have caught the transcript annexure's first life, in which a
    complete and tested module was never reached by `ReportBuilder.build` and every report silently
    dropped it while three surfaces promised the office's copy would carry it.
    """
    data = _data()
    attach_ai_layers(data, [_layer()])
    template = apply_report_settings(TEMPLATES[0], None, include_ai_layers=True)
    doc, _warnings = build_report(
        data, TEMPLATES[0].id, _resolver, meta=_meta(), template=template
    )
    text = _text_of(doc.blocks)
    assert "AI-cleaned transcript" in text
    assert "dabu" in text            # the artisan's words survived the render
    assert "A. Designer" in text     # and somebody's name is against them


def test_a_report_not_asked_for_the_annexure_carries_nothing_of_it():
    """Even with layers attached and accepted. The toggle is the whole of the decision."""
    data = _data()
    attach_ai_layers(data, [_layer()])
    doc, _warnings = build_report(data, TEMPLATES[0].id, _resolver, meta=_meta())
    assert "AI-cleaned transcript" not in _text_of(doc.blocks)


def test_the_section_renders_nothing_when_nothing_was_accepted():
    """A designer who asks for the annexure and has accepted nothing gets the report they'd have got.

    Not an empty heading, and not a page break with nothing after it — a blank annexure in a
    sixty-page document reads as a rendering failure.
    """
    data = _data()
    attach_ai_layers(data, [_layer(accepted=False)])
    template = apply_report_settings(TEMPLATES[0], None, include_ai_layers=True)
    asked, _w = build_report(data, TEMPLATES[0].id, _resolver, meta=_meta(), template=template)
    plain, _w2 = build_report(_data(), TEMPLATES[0].id, _resolver, meta=_meta())
    assert len(asked.blocks) == len(plain.blocks)


def test_layers_travel_on_the_workshop_data_without_being_declared_on_it():
    data = _data()
    assert ai_layers_of(data) == ()
    attach_ai_layers(data, [_layer()])
    assert len(ai_layers_of(data)) == 1
