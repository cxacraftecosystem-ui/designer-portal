import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_builder import WorkshopData, build_report
from app.services.report_model import (
    HeadingBlock,
    ImageRef,
    PageBreakBlock,
    ParagraphBlock,
    ReportMeta,
    TableBlock,
    runs_text,
)
from app.services.report_questionnaires import (
    MAX_ROWS_PER_SITTING,
    MAX_SITTINGS_PER_QUESTIONNAIRE,
    NOT_RECORDED,
    RETIRED_NOTE,
    QuestionnaireAnswer,
    QuestionnaireItem,
    QuestionnaireSitting,
    attach_questionnaires,
    questionnaire_annexure_blocks,
    questionnaire_warnings,
    questionnaires_of,
)
from app.services.report_templates import TEMPLATES, SpecialSection
from app.services.rich_text import Mark, RichBlock, RichDoc, RichSpan, to_stored_text


def _meta() -> ReportMeta:
    return ReportMeta(title="Workshop", subtitle="Cluster",
                      generated_at="2026-08-08T00:00:00Z")


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/jpeg")


def _answer(prompt: str, text: str = "", **kw) -> QuestionnaireAnswer:
    base = {"section_code": "A", "section_title": "Household"}
    base.update(kw)
    return QuestionnaireAnswer(prompt=prompt, answer_text=text, **base)


def _sitting(name: str = "Ramesh Meher", answers=(), **kw) -> QuestionnaireSitting:
    base = {"entry_id": "entry-1", "title": "Sitting 1", "source": "APP",
            "recorded_at": "2026-02-11T09:30:00+00:00", "recorded_by": "A Designer"}
    base.update(kw)
    return QuestionnaireSitting(respondent_name=name, answers=tuple(answers), **base)


def _item(sittings=(), **kw) -> QuestionnaireItem:
    base = {"questionnaire_id": "cq-1", "title": "Loom and livelihood survey",
            "version": 3, "question_count": 2, "source_filename": "loom-survey.xlsx"}
    base.update(kw)
    return QuestionnaireItem(sittings=tuple(sittings), **base)


def _filled() -> QuestionnaireItem:
    return _item([
        _sitting(answers=[
            _answer("How many looms do you own?", "12"),
            _answer("Who else weaves in the household?", "My wife and my elder son"),
        ]),
    ])


def _document(items, template_id: str = "DCH_STANDARD"):
    data = WorkshopData(workshop_id="w1", title="Workshop")
    if items is not None:
        attach_questionnaires(data, items)
    document, warnings = build_report(data, template_id, _resolver, meta=_meta())
    return document, warnings


def _text(document) -> str:
    out: list[str] = []
    for block in document.blocks:
        if isinstance(block, (HeadingBlock, ParagraphBlock)):
            out.append(runs_text(block.runs))
        elif isinstance(block, TableBlock):
            out.extend(runs_text(c) for row in block.rows for c in row)
            out.extend(col.header for col in block.columns)
            out.append(block.caption)
    return "\n".join(out)



def test_every_template_carries_the_questionnaire_annexure():
    for report_template in TEMPLATES:
        specials = [s.special for s in report_template.sections]
        assert SpecialSection.ANNEXURE_QUESTIONNAIRES in specials, (
            f"{report_template.id} does not carry the questionnaire annexure, so a workshop whose "
            "designer recorded a whole survey against their own form prints none of it"
        )


def test_the_annexure_sits_before_the_transcripts_in_every_template():
    for report_template in TEMPLATES:
        specials = [s.special for s in report_template.sections]
        assert (specials.index(SpecialSection.ANNEXURE_QUESTIONNAIRES)
                < specials.index(SpecialSection.ANNEXURE_TRANSCRIPTS)), report_template.id



def test_the_answers_reach_a_generated_report():
    document, _warnings = _document([_filled()])
    text = _text(document)
    assert "Annexure — Questionnaire responses" in text
    assert "Loom and livelihood survey" in text
    assert "Ramesh Meher" in text
    assert "How many looms do you own?" in text
    assert "12" in text
    assert "My wife and my elder son" in text


def test_the_provenance_names_the_questionnaire_and_its_version():
    text = _text(_document([_filled()])[0])
    assert "questionnaire cq-1" in text
    assert "version 3" in text
    assert "loom-survey.xlsx" in text


def test_nothing_attached_appends_nothing_at_all():
    without, _ = _document(None)
    empty, _ = _document([])
    assert without.blocks == empty.blocks
    assert not any(
        isinstance(b, HeadingBlock) and "Questionnaire responses" in runs_text(b.runs)
        for b in without.blocks
    )


def test_an_attached_but_unanswered_questionnaire_prints_no_heading_and_warns_instead():
    empty = _item([_sitting(answers=[_answer("How many looms do you own?", "")])])
    document, _ = _document([empty])
    assert "Loom and livelihood survey" not in _text(document)

    warnings = questionnaire_warnings([empty])
    assert len(warnings) == 1
    assert "Loom and livelihood survey" in warnings[0]
    assert questionnaire_warnings([_filled()]) == []


def test_attach_and_read_round_trip_on_the_real_workshop_data():
    data = WorkshopData(workshop_id="w1", title="Workshop")
    assert questionnaires_of(data) == ()
    attach_questionnaires(data, [_filled()])
    assert len(questionnaires_of(data)) == 1
    assert questionnaires_of(data)[0].questionnaire_id == "cq-1"



def test_a_reworded_question_prints_under_the_wording_its_answer_was_given_under():
    item = _item([
        _sitting(answers=[
            _answer("How many looms do you own?", "12", is_retired=True),
        ]),
    ])
    text = _text(_document([item])[0])
    assert "How many looms do you own?" in text
    assert RETIRED_NOTE in text


def test_a_required_question_left_blank_prints_as_a_visible_gap():
    item = _item([
        _sitting(answers=[
            _answer("How many looms do you own?", "12"),
            _answer("What is your monthly income?", "", is_required=True),
            _answer("Any other trade?", ""),
        ]),
    ])
    text = _text(_document([item])[0])
    assert NOT_RECORDED in text
    assert "What is your monthly income?" in text
    assert "Any other trade?" not in text, (
        "an unanswered optional question printed a row, which turns an eight-answer sitting into a "
        "forty-row table of blanks"
    )


def test_a_retired_required_question_nobody_answered_prints_no_row_at_all():
    item = _item([
        _sitting(answers=[
            _answer("Who else weaves in the household?", "My wife"),
            _answer("How many looms do you own?", "", is_required=True, is_retired=True),
            _answer("How many looms did you own?", "9", is_required=True, is_retired=True),
            _answer("What is your monthly income?", "", is_required=True),
        ]),
    ])
    text = _text(_document([item])[0])
    assert "How many looms do you own?" not in text, (
        "a required question that was reworded before this sitting printed a Not recorded. row, so "
        "the annexure asserts a gap in fieldwork that never happened"
    )
    assert "How many looms did you own?" in text and RETIRED_NOTE in text
    assert "What is your monthly income?" in text and NOT_RECORDED in text


def test_the_interviewers_note_prints_with_the_answer_it_belongs_to():
    item = _item([
        _sitting(answers=[
            _answer("How many looms do you own?", "12",
                    notes="Two are lent to a neighbour this season."),
        ]),
    ])
    text = _text(_document([item])[0])
    assert "Two are lent to a neighbour this season." in text


def test_each_section_of_the_form_is_labelled_and_no_two_tables_are_adjacent():
    item = _item([
        _sitting(answers=[
            _answer("How many looms do you own?", "12"),
            _answer("Where do you sell?", "The Sunday haat",
                    section_code="B", section_title="Market"),
        ]),
    ])
    blocks = questionnaire_annexure_blocks([item])
    text = "\n".join(
        runs_text(b.runs) for b in blocks if isinstance(b, (HeadingBlock, ParagraphBlock))
    )
    assert "A — Household" in text
    assert "B — Market" in text
    for first, second in zip(blocks, blocks[1:]):
        assert not (isinstance(first, TableBlock) and isinstance(second, TableBlock)), (
            "two adjacent tables: Word merges them, so two sections of the form become one"
        )


def test_the_page_break_is_the_sections_to_give_and_not_the_modules_to_assume():
    with_break = questionnaire_annexure_blocks([_filled()], page_break_before=True)
    without = questionnaire_annexure_blocks([_filled()], page_break_before=False)
    assert isinstance(with_break[0], PageBreakBlock)
    assert not isinstance(without[0], PageBreakBlock)



def test_a_runaway_sitting_is_truncated_and_the_document_says_where_it_stopped():
    answers = [_answer(f"Question {i}", f"Answer {i}") for i in range(MAX_ROWS_PER_SITTING + 25)]
    text = _text(_document([_item([_sitting(answers=answers)])])[0])
    assert f"Answers truncated after {MAX_ROWS_PER_SITTING} questions" in text
    assert f"Answer {MAX_ROWS_PER_SITTING - 1}" in text
    assert f"Answer {MAX_ROWS_PER_SITTING + 10}" not in text


def test_too_many_sittings_are_capped_and_counted_rather_than_dropped():
    sittings = [
        _sitting(f"Respondent {i}", entry_id=f"entry-{i}",
                 answers=[_answer("How many looms do you own?", str(i))])
        for i in range(MAX_SITTINGS_PER_QUESTIONNAIRE + 4)
    ]
    text = _text(_document([_item(sittings)])[0])
    assert "4 further sittings were recorded" in text
    assert f"Respondent {MAX_SITTINGS_PER_QUESTIONNAIRE - 1}" in text
    assert f"Respondent {MAX_SITTINGS_PER_QUESTIONNAIRE + 1}" not in text


def test_a_sitting_with_no_respondent_name_is_still_titled_by_something_a_reader_can_use():
    item = _item([_sitting("", title="Interview at the co-operative hall",
                           answers=[_answer("How many looms do you own?", "12")])])
    assert "Interview at the co-operative hall" in _text(_document([item])[0])



class _Doc:
    def __init__(self, blocks) -> None:
        self.blocks = blocks


def _all_text(blocks) -> str:
    return _text(_Doc(blocks))


def _stored(*paragraphs: str) -> str:
    return to_stored_text(
        RichDoc(
            blocks=tuple(
                RichBlock(spans=(RichSpan(text, marks=frozenset({Mark.BOLD})),))
                for text in paragraphs
            )
        )
    )


class TestRichTextAnswers:
    def test_a_formatted_answer_prints_its_prose(self):
        item = _item(sittings=[_sitting(answers=[_answer("How many looms?", _stored("Twelve looms"))])])
        text = _all_text(questionnaire_annexure_blocks([item]))
        assert "Twelve looms" in text

    def test_and_never_prints_the_documents_own_json(self):
        item = _item(sittings=[_sitting(answers=[_answer("How many looms?", _stored("Twelve looms"))])])
        text = _all_text(questionnaire_annexure_blocks([item]))
        assert "{'blocks'" not in text
        assert '{"blocks"' not in text
        assert "PARAGRAPH" not in text

    def test_a_multi_paragraph_answer_keeps_both_paragraphs(self):
        item = _item(
            sittings=[
                _sitting(answers=[_answer("Tell me about the loom", _stored("It was my mother's", "She wove on it"))])
            ]
        )
        text = _all_text(questionnaire_annexure_blocks([item]))
        assert "It was my mother's" in text
        assert "She wove on it" in text

    def test_plain_prose_is_untouched(self):
        item = _item(sittings=[_sitting(answers=[_answer("How many looms?", "Twelve looms")])])
        text = _all_text(questionnaire_annexure_blocks([item]))
        assert "Twelve looms" in text

    def test_an_empty_document_counts_as_unanswered(self):
        blank = _answer("How many looms?", '{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}')
        assert blank.has_answer is False
        sitting = _sitting(answers=[blank])
        assert sitting.answered_count == 0
        assert questionnaire_annexure_blocks([_item(sittings=[sitting])]) == ()

    def test_a_formatted_answer_is_counted_as_one(self):
        sitting = _sitting(answers=[_answer("How many looms?", _stored("Twelve looms"))])
        assert sitting.answered_count == 1
        assert _item(sittings=[sitting]).answered_total == 1
