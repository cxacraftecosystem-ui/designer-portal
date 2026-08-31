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
from app.services.rich_text import Mark, RichBlock, RichDoc, RichSpan, to_stored_text

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


def test_a_retired_required_question_nobody_answered_prints_no_row_at_all():
    """The intersection the two tests above each cover one half of, and it was the defect.

    ``prints`` was ``has_answer or is_required`` — retirement was never consulted — and nothing
    upstream clears ``isRequired`` when a question is retired: ``supersede_question`` copies it onto
    the replacement and leaves it set on the original, so a REQUIRED question that was reworded is
    permanently ``required, unanswered, retired`` for every sitting recorded afterwards. Twelve
    sittings taken after one rewording each grew a row reading "How many looms do you own?
    (no longer asked) | Not recorded." — a respondent who was never shown the question, recorded as
    having left it blank, in an annexure whose own lead paragraph tells the reader that a blank means
    a gap in the fieldwork.

    The rule is stated in the loader (``questionnaire_forms.report_items``: retired questions are
    "printed where they carry an answer") and implemented correctly in the sibling renderer
    (``report_custom_sections.printed_fields``). Both other arms are asserted here too, because the
    cheap way to pass this test is to stop printing retired questions altogether, which would delete
    the evidence the supersede rule exists to keep.
    """
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
    # The two arms this must not have broken to get there.
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


# --------------------------------------------------------------------------------------
# A rich-text answer, printed as prose and never as its own JSON
# --------------------------------------------------------------------------------------
#
# WHAT THIS DEFENDS AGAINST, AND IT IS THE FAILURE ``report_builder.format_value`` ALREADY RECORDS
# AT ITS OWN RICH_TEXT BRANCH. A ``String?`` column in this repository can hold one of three things —
# NULL, prose, or a whole serialised document as ``{"blocks":[…]}`` — told apart only by looking at
# the value (``rich_text``'s "rich text inside a plain String column" section carries the encoding).
# This module read the third with ``runs_of(answer.answer_text.strip())``, which stringifies whatever
# it is given, so a stored document printed as the literal text
# ``{"blocks": [{"kind": "PARAGRAPH", …}]}`` where the artisan's answer should have been — inside a
# .docx handed to an officer.
#
# AND NOTHING WOULD HAVE REPORTED A PROBLEM, which is the half that makes it dangerous rather than
# merely ugly: ``has_answer`` was ``bool(answer_text.strip())``, and a JSON-shaped string is not
# blank, so every emptiness check above it read the field as FILLED. The count on the sitting's
# provenance line, the "Answers recorded" column of the index table and ``questionnaire_warnings``
# would all have agreed the fieldwork was there.
#
# WHICH COLUMN, PRECISELY. The annexure renders ``QuestionnaireFormAnswer.answerText`` — the
# designer's own uploaded form. The box that became a ``RichTextField`` on 2026-08-31 is its sibling,
# ``QuestionnaireResponse.answerText`` on ``/questionnaire`` (singular), and as of that date no
# client writes a document into the column tested here. :attr:`QuestionnaireAnswer.plain_answer`
# argues why the guard belongs at this render boundary anyway; these tests pin the behaviour, so the
# day the two boxes converge nobody has to notice.


class _Doc:
    """A stand-in document, so ``_text`` above can be reused over a bare block tuple.

    ``questionnaire_annexure_blocks`` returns blocks rather than a document (deliberately — see its
    docstring), and ``_text`` reads only ``.blocks``.
    """

    def __init__(self, blocks) -> None:
        self.blocks = blocks


def _all_text(blocks) -> str:
    return _text(_Doc(blocks))


def _stored(*paragraphs: str) -> str:
    """What the web editor actually writes into the column for a FORMATTED answer.

    Built through ``rich_text`` rather than hand-typed, so this fixture cannot drift from the shape
    the encoder produces — ``test_rich_text_stored_columns`` pins that the two agree.
    """
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
        """The single assertion this whole section exists for. ``{'blocks'`` in either quoting — a
        Python dict repr or the stored JSON — is the string a reader would have met in the file."""
        item = _item(sittings=[_sitting(answers=[_answer("How many looms?", _stored("Twelve looms"))])])
        text = _all_text(questionnaire_annexure_blocks([item]))
        assert "{'blocks'" not in text
        assert '{"blocks"' not in text
        assert "PARAGRAPH" not in text

    def test_a_multi_paragraph_answer_keeps_both_paragraphs(self):
        """``plain_runs`` collapses paragraph breaks to single spaces — the trade a table cell forces,
        documented at that function — but it must not DROP one."""
        item = _item(
            sittings=[
                _sitting(answers=[_answer("Tell me about the loom", _stored("It was my mother's", "She wove on it"))])
            ]
        )
        text = _all_text(questionnaire_annexure_blocks([item]))
        assert "It was my mother's" in text
        assert "She wove on it" in text

    def test_plain_prose_is_untouched(self):
        """The identity guarantee. Every answer recorded before the editor existed must render
        byte-for-byte as it did, which is why the flattening goes through ``plain_from_stored`` and
        the runs branch on ``stored_text_document`` rather than pushing prose through the document
        model."""
        item = _item(sittings=[_sitting(answers=[_answer("How many looms?", "Twelve looms")])])
        text = _all_text(questionnaire_annexure_blocks([item]))
        assert "Twelve looms" in text

    def test_an_empty_document_counts_as_unanswered(self):
        """A rich-text box that was focused and left alone still saves
        ``{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}``. Read raw that string is truthy, so the row
        printed an empty answer cell AND was counted as answered — a claim about fieldwork that never
        happened, in a document nobody can check against the sitting."""
        blank = _answer("How many looms?", '{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}')
        assert blank.has_answer is False
        sitting = _sitting(answers=[blank])
        assert sitting.answered_count == 0
        # An unanswered OPTIONAL question prints nothing at all, so the whole sitting drops out and
        # the annexure renders no heading — exactly as it does for a sitting nobody filled in.
        assert questionnaire_annexure_blocks([_item(sittings=[sitting])]) == ()

    def test_a_formatted_answer_is_counted_as_one(self):
        """The counts read the flattened text too, so the index table and the provenance line agree
        with what is printed under them."""
        sitting = _sitting(answers=[_answer("How many looms?", _stored("Twelve looms"))])
        assert sitting.answered_count == 1
        assert _item(sittings=[sitting]).answered_total == 1
