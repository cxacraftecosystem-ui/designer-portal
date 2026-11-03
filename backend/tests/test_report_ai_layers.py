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



def test_an_unaccepted_layer_prints_nothing_at_all():
    assert ai_layer_annexure_blocks([_layer(accepted=False)]) == ()


def test_an_accepted_layer_with_no_text_prints_nothing_but_is_reported():
    tags = _layer(kind="TAGS", text="")
    assert ai_layer_annexure_blocks([tags]) == ()
    assert any("carries no text" in w for w in annexure_warnings([tags]))


def test_the_designer_is_told_what_acceptance_left_out():
    warnings = annexure_warnings([_layer(), _layer(layer_id="l2", accepted=False)])
    assert any("nobody has accepted it yet" in w for w in warnings)
    assert any("accept the ones this report should carry" in w for w in warnings)


def test_a_fully_accepted_set_raises_no_warning():
    assert annexure_warnings([_layer(), _layer(layer_id="l2")]) == []



def test_an_expansion_carries_a_caution_above_the_prose_and_no_other_kind_does():
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
    from app.services.report_ai_layers import SPEAKER_NOTE

    text = _text_of(ai_layer_annexure_blocks([_layer()]))
    assert SPEAKER_NOTE in text
    assert text.index(SPEAKER_NOTE) < text.index("How is the dabu paste made?")


def test_a_layer_with_no_speaker_turns_carries_no_caution_about_them():
    from app.services.report_ai_layers import SPEAKER_NOTE

    text = _text_of(ai_layer_annexure_blocks([_layer(text="The dabu paste is mixed with gum.")]))
    assert SPEAKER_NOTE not in text


def test_turns_a_designer_typed_are_never_described_as_a_machines_guess():
    from app.services.report_ai_layers import SPEAKER_NOTE, speaker_labels_are_guessed

    typed = _layer(
        kind="PROOFREAD", source_kind="SUPPLIED_TEXT", source_id="",
        text="**Rita:** The vat is set at dawn.\n\n**Devi:** With indigo.",
    )
    assert speaker_labels_are_guessed(typed) is False
    assert SPEAKER_NOTE not in _text_of(ai_layer_annexure_blocks([typed]))


def test_a_withheld_passage_is_not_cautioned_about_labels_it_does_not_print():
    from app.services.report_ai_layers import (
        SPEAKER_NOTE,
        WITHHELD_NOTE,
        speaker_labels_are_guessed,
    )

    assert speaker_labels_are_guessed(_layer(text="", text_withheld=True)) is False

    still_carrying_turns = _layer(text_withheld=True)
    assert "**Artisan:**" in still_carrying_turns.text, "the fixture must actually carry turns"
    assert speaker_labels_are_guessed(still_carrying_turns) is False
    page = _text_of(ai_layer_annexure_blocks([still_carrying_turns]))
    assert WITHHELD_NOTE in page
    assert SPEAKER_NOTE not in page
    assert "How is the dabu paste made?" not in page


def test_the_designer_is_warned_when_invented_prose_is_going_into_the_file():
    warnings = annexure_warnings([_layer(kind="EXPANDED")])
    assert any("WRITTEN by a model" in w for w in warnings)
    assert any("withdrawing an acceptance" in w.lower() for w in warnings)


def test_a_translation_prints_both_languages_and_never_only_the_one_it_is_in():
    text = _text_of(ai_layer_annexure_blocks([
        _layer(kind="TRANSLATION", language="en", source_language="or", target_language="en"),
    ]))
    assert "translated from or into en" in text


def test_a_translation_whose_source_language_nobody_recorded_says_so_in_words():
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
    text = _text_of(ai_layer_annexure_blocks([
        _layer(kind="PROOFREAD", source_kind="SUPPLIED_TEXT", source_id="",
               source_label="dabu " * 300),
    ]))
    assert "made from the note" in text
    assert "…" in text



def test_every_kind_is_titled_as_machine_work():
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
    for tier, expected in (("TIER_1", "handset"), ("TIER_2", "handset"), ("TIER_3", "online")):
        assert expected in tier_words(tier)
        assert "Tier" not in tier_words(tier)
    assert "cannot identify" in tier_words("TIER_9")


def test_the_index_table_names_the_tier_and_the_acceptor_for_every_row():
    text = _text_of(ai_layer_annexure_blocks([_layer()]))
    assert "online, by a hosted model" in text
    assert "A. Designer" in text



def test_an_unrecorded_model_is_said_in_words_not_left_blank():
    line = provenance_line(_layer(provider="UNRECORDED", model_id="UNRECORDED"))
    assert "the model was not recorded" in line
    assert "UNRECORDED" not in line


def test_a_recorded_model_and_version_both_reach_the_page():
    line = provenance_line(_layer(model_id="nova-3", model_version="2026-02"))
    assert "nova-3 2026-02" in line


def test_multi_is_explained_rather_than_printed_as_a_token():
    assert "several languages, interleaved" in provenance_line(_layer(language="multi"))
    assert "in Odia" in provenance_line(_layer(language="Odia"))


def test_the_provenance_names_who_accepted_it_and_when():
    line = provenance_line(_layer())
    assert "Accepted by A. Designer on 2026-08-12" in line


def test_an_unresolved_acceptor_is_printed_as_an_account_and_never_as_a_bare_id():
    line = provenance_line(_layer(accepted_by="", accepted_by_id="cmld8x0a10000gzsy4t9v2b1q"))
    assert "cmld8x0a10000gzsy4t9v2b1q" in line
    assert "(account cmld8x0a10000gzsy4t9v2b1q)" in line
    assert "Accepted by cmld8x0a10000gzsy4t9v2b1q" not in line


def test_a_withheld_layer_keeps_its_provenance_and_loses_only_the_recordings_content():
    withheld = _layer(text="", text_withheld=True)
    blocks = ai_layer_annexure_blocks([withheld])
    text = _text_of(blocks)
    assert "AI-cleaned transcript" in text
    assert "online, by a hosted model" in text
    assert "A. Designer" in text
    assert "may not read" in text
    assert "dabu" not in text
    assert "gum arabic" not in text


def test_a_withheld_layer_is_still_dropped_when_nobody_accepted_it():
    assert ai_layer_annexure_blocks([_layer(text="", text_withheld=True, accepted=False)]) == ()


def test_no_wording_in_the_annexure_claims_the_whole_document_is_listed_here():
    text = _text_of(ai_layer_annexure_blocks([_layer()]))
    assert "anywhere in this document" not in text
    assert "Nothing a machine produced" not in text
    assert "read against its source and accepted" in text


def test_the_source_is_named_so_a_reader_can_trace_it_back():
    assert "Artisan's spoken explanation" in provenance_line(_layer())
    assert "media_7" in provenance_line(_layer(source_label=""))



def test_no_template_carries_the_section_by_default():
    for template in TEMPLATES:
        assert SpecialSection.ANNEXURE_AI_LAYERS not in [s.special for s in template.sections]


def test_the_template_is_returned_unchanged_when_nobody_asks():
    template = TEMPLATES[0]
    assert apply_report_settings(template, None) is template
    assert apply_report_settings(template, None, include_ai_layers=None) is template
    assert apply_report_settings(template, None, include_ai_layers=False) is template


def test_asking_for_it_splices_exactly_one_section():
    sections = apply_report_settings(TEMPLATES[0], None, include_ai_layers=True).sections
    specials = [s.special for s in sections]
    assert specials.count(SpecialSection.ANNEXURE_AI_LAYERS) == 1


def test_the_annexure_never_interrupts_the_narrative():
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



def _data() -> WorkshopData:
    return WorkshopData(workshop_id="w1", title="Workshop")


def _resolver(media_id: str):
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/png")


def _meta() -> ReportMeta:
    return ReportMeta(title="Design & Prototype Workshop")


def test_a_report_asked_for_the_annexure_carries_the_accepted_layer():
    data = _data()
    attach_ai_layers(data, [_layer()])
    template = apply_report_settings(TEMPLATES[0], None, include_ai_layers=True)
    doc, _warnings = build_report(
        data, TEMPLATES[0].id, _resolver, meta=_meta(), template=template
    )
    text = _text_of(doc.blocks)
    assert "AI-cleaned transcript" in text
    assert "dabu" in text
    assert "A. Designer" in text


def test_a_report_not_asked_for_the_annexure_carries_nothing_of_it():
    data = _data()
    attach_ai_layers(data, [_layer()])
    doc, _warnings = build_report(data, TEMPLATES[0].id, _resolver, meta=_meta())
    assert "AI-cleaned transcript" not in _text_of(doc.blocks)


def test_the_section_renders_nothing_when_nothing_was_accepted():
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
