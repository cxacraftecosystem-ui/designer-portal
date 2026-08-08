"""The questionnaire annexure: the survey's own recorded answers reaching a submitted report.

WHAT THIS PREVENTS, AND IT HAD ALREADY HAPPENED. Stages 7 and 8 of a design workshop are about
nothing but the survey, and a designer builds their own instrument for it — the .xlsx pro-forma,
uploaded, attached to the workshop from a dropdown, answered in the app. Not one of those answers
reached the report: ``questionnaire`` appeared nowhere in ``report_builder``, ``report_templates`` or
``design_workshops``, no REF field pointed at it, and ``REFERENCE_MODELS`` registers five models of
which none is a questionnaire. So the report described a survey whose responses sat in a table it
never opened, and nothing anywhere said so — the exact silent-omission shape as the transcript
annexure, which was a whole module with no call site for the length of its first life.

Every test below therefore defends one of two properties:

* THE ANSWERS REACH A REAL DOCUMENT, through ``build_report`` and the template catalogue rather than
  by calling the annexure builder directly. A module that renders correctly and is reached by
  nothing is the defect this feature exists to close, and calling it directly would pass against
  exactly that.
* WHAT IS PRINTED IS WHAT WAS RECORDED. The wording an answer was given under, the blank that a
  required question was left as, and nothing invented in between.
"""

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

# --------------------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------------------


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
    """Every run of every block, flattened — what a reader actually sees."""
    out: list[str] = []
    for block in document.blocks:
        if isinstance(block, (HeadingBlock, ParagraphBlock)):
            out.append(runs_text(block.runs))
        elif isinstance(block, TableBlock):
            out.extend(runs_text(c) for row in block.rows for c in row)
            out.extend(col.header for col in block.columns)
            out.append(block.caption)
    return "\n".join(out)


# --------------------------------------------------------------------------------------
# The section exists at all, and every template carries it
# --------------------------------------------------------------------------------------


def test_every_template_carries_the_questionnaire_annexure():
    """A section carried by five of six templates is a section one office silently never sees.

    Pinned across the whole catalogue rather than on one template for the reason the transcript
    annexure is carried by all six: which office asked for the report does not change whether the
    survey's own answers belong in it.
    """
    for report_template in TEMPLATES:
        specials = [s.special for s in report_template.sections]
        assert SpecialSection.ANNEXURE_QUESTIONNAIRES in specials, (
            f"{report_template.id} does not carry the questionnaire annexure, so a workshop whose "
            "designer recorded a whole survey against their own form prints none of it"
        )


def test_the_annexure_sits_before_the_transcripts_in_every_template():
    """The instrument the designer wrote and administered, then whatever a microphone caught."""
    for report_template in TEMPLATES:
        specials = [s.special for s in report_template.sections]
        assert (specials.index(SpecialSection.ANNEXURE_QUESTIONNAIRES)
                < specials.index(SpecialSection.ANNEXURE_TRANSCRIPTS)), report_template.id


# --------------------------------------------------------------------------------------
# Reaching a real document
# --------------------------------------------------------------------------------------


def test_the_answers_reach_a_generated_report():
    """THE WHOLE FEATURE, asserted through ``build_report`` and not through the annexure builder.

    ``append_questionnaire_annexure`` rendering correctly proves nothing on its own — the transcript
    annexure did that for months while ``ReportBuilder.build`` had no branch for it and every report
    dropped the appendix in silence. This goes in through the template catalogue, so it fails if the
    branch is ever removed.
    """
    document, _warnings = _document([_filled()])
    text = _text(document)
    assert "Annexure — Questionnaire responses" in text
    assert "Loom and livelihood survey" in text
    assert "Ramesh Meher" in text
    assert "How many looms do you own?" in text
    assert "12" in text
    assert "My wife and my elder son" in text


def test_the_provenance_names_the_questionnaire_and_its_version():
    """A file handed to an office has to be matchable back to what it was built from.

    ``Questionnaire.version`` counts the supersedes and retires applied after the first answer
    existed, so two reports of one workshop that disagree can be told apart without opening the
    database — the same job ``report_annexures._provenance`` does with a recording's media id.
    """
    text = _text(_document([_filled()])[0])
    assert "questionnaire cq-1" in text
    assert "version 3" in text
    assert "loom-survey.xlsx" in text


def test_nothing_attached_appends_nothing_at_all():
    """Not even the page break, so every existing template is byte-for-byte what it was.

    Most workshops have no questionnaire attached. If this section cost them a blank page with a
    heading on it, the feature would have made every report in the archive worse.
    """
    without, _ = _document(None)
    empty, _ = _document([])
    assert without.blocks == empty.blocks
    assert not any(
        isinstance(b, HeadingBlock) and "Questionnaire responses" in runs_text(b.runs)
        for b in without.blocks
    )


def test_an_attached_but_unanswered_questionnaire_prints_no_heading_and_warns_instead():
    """An empty heading in an appendix of evidence reads as a rendering fault.

    The designer chose that form for this workshop, so silence is wrong in the other direction too:
    they would otherwise have to notice the shortfall themselves in a sixty-page document.
    """
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


# --------------------------------------------------------------------------------------
# What is printed is what was recorded
# --------------------------------------------------------------------------------------


def test_a_reworded_question_prints_under_the_wording_its_answer_was_given_under():
    """THE FAILURE THE SCHEMA ITSELF NAMES, arriving by a different door.

    ``QuestionnaireFormQuestion.supersededById`` exists because "How many looms?" answered "12" and
    later reworded to "How many weavers work with you?" makes the repository assert twelve weavers —
    and the schema comment names a ministry report as where that lands. The annexure prints the
    retired wording, marked, or it re-opens exactly that hole.
    """
    item = _item([
        _sitting(answers=[
            _answer("How many looms do you own?", "12", is_retired=True),
        ]),
    ])
    text = _text(_document([item])[0])
    assert "How many looms do you own?" in text
    assert RETIRED_NOTE in text


def test_a_required_question_left_blank_prints_as_a_visible_gap():
    """The report's own editorial rule, applied to the survey's answers.

    An empty OPTIONAL field prints nothing; an empty REQUIRED one prints "Not recorded.", so a gap
    in the record is visible as a gap rather than as an absence. A sitting that skipped a required
    question is a fact about the fieldwork.
    """
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
    """Both writers refuse two adjacent tables — Word merges them into one.

    The per-section label is the paragraph that has to be there anyway, so this asserts the layout
    rule and the editorial one at once: a reader can tell which section of the form an answer came
    from, and the .docx does not collapse two sections into a single table.
    """
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


# --------------------------------------------------------------------------------------
# The caps, and that they say so
# --------------------------------------------------------------------------------------


def test_a_runaway_sitting_is_truncated_and_the_document_says_where_it_stopped():
    """A missing half of a respondent's answers that nobody is told about is worse than a note."""
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
    assert "4 further sitting(s) were recorded" in text
    assert f"Respondent {MAX_SITTINGS_PER_QUESTIONNAIRE - 1}" in text
    assert f"Respondent {MAX_SITTINGS_PER_QUESTIONNAIRE + 1}" not in text


def test_a_sitting_with_no_respondent_name_is_still_titled_by_something_a_reader_can_use():
    item = _item([_sitting("", title="Interview at the co-operative hall",
                           answers=[_answer("How many looms do you own?", "12")])])
    assert "Interview at the co-operative hall" in _text(_document([item])[0])
