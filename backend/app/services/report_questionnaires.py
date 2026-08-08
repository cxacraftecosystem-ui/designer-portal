"""The questionnaire annexure: every sitting recorded against this workshop's own questionnaires.

WHAT THIS IS FOR. Stages 7 and 8 of a design workshop are about the survey — stage 7 declares what
the survey is meant to find out and types the questions into one RICH_TEXT box, stage 8 records what
the field visits found. Separately from both, a designer builds their own instrument: they download
the .xlsx pro-forma, type their questions into it, upload it, attach it to this workshop from the
dropdown (``Questionnaire.designWorkshopId``) and then record sittings against it in the app. Until
now the report described a survey whose actual responses lived in a table it never opened: the word
"questionnaire" did not appear anywhere in ``report_builder`` or ``report_templates``, and no REF
field, no template section and no hydration mapping reached ``QuestionnaireFormAnswer``.

WHY AN ANNEXURE AND NOT A REFERENCE MODEL. A REF field stores ONE id, and
``design_workshops.REFERENCE_HYDRATION`` copies a handful of SCALAR display fields off the record it
names onto the stage entry. A questionnaire is not that shape: it is a set of sittings times a set of
questions, a two-dimensional table whose size is decided by the fieldwork rather than by the
registry. Flattening it into the ``data`` JSON of one ``DwStageEntry`` would store rows that
``validate_entry`` cannot check (there is no ``FieldSpec`` per question — the questions are authored
by a designer at runtime), and hydration copies at PICK time, so the report would have shown the
answers as they stood the moment the designer chose the questionnaire and silently omitted every
answer recorded afterwards, which is the whole of the fieldwork.

WHY AN ANNEXURE AND NOT A SECTION IN THE BODY. Stage 7 already prints the designer's typed list of
questions and stage 8 already prints the ``surveyResponse`` rows. A third structured answer table
between them makes a reader ask which of the three is "the survey". The body says what the survey was
and what it found; this is the evidence behind it, verbatim, at the back — exactly the argument the
transcript annexure beside it makes, and for the same reason: a set of sittings, like a set of
recordings, is unbounded in length and is read as proof rather than as narrative.

WHY THERE IS NO STAGE-20 TOGGLE, unlike every other annexure. ``includeTranscripts`` exists because
transcripts are produced AUTOMATICALLY by the media queue from recordings a designer made for other
reasons, so an annexure of them is something that could happen TO a designer rather than something
they asked for. A questionnaire sitting has no such path: the designer built the form, attached it to
THIS workshop from a dropdown, and typed the answers in. **The attachment is the opt-in**, and adding
a switch to un-say it would be a second, contradictory answer to a question already asked. Detaching
the questionnaire (``designWorkshopId = null``, which the API already supports) takes it out.

WHY THE ANSWERS ARE READ AT GENERATE TIME, AND WHY THAT DOES NOT BREAK THE SAVE-TIME-COPY RULE.
``report_builder.ReferencedRecord`` states the rule this pipeline lives by: a submitted report must
never re-resolve a NAME through a live table, because the artisan record behind it is live data that
gets corrected, merged and deleted, and any of those would silently rewrite a document already handed
to an officer. That rule is about a DENORMALISED DISPLAY COPY of a record owned somewhere else. A
questionnaire answer is not a display copy of anything — it IS the primary record of the fieldwork,
and the protection the rule asks for is already built one layer down, in the tables themselves:
``QuestionnaireFormQuestion.supersededById`` exists precisely so that rewording an answered question
CANNOT change what its recorded answer asserts, and the schema names the failure it prevents in those
words ("… and a ministry report now states there are twelve weavers"). Answers are therefore loaded
once, before the synchronous render starts, exactly as the transcripts are, and this module performs
no lookup at all. What the document states about its own provenance — the questionnaire id, its
``version`` and the sitting count — is printed under each heading so a file can be matched back to
what it was built from, which is the same job ``report_annexures._provenance`` does for a recording.

NOTHING HERE DECIDES WHETHER THE ANNEXURE IS WANTED. An annexure with no items renders no heading and
no page break at all, so a workshop with no questionnaire attached produces byte-for-byte the report
it produced before this module existed.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.services.report_model import (
    Align,
    Block,
    DocumentBuilder,
    PageBreakBlock,
    ParagraphBlock,
    ParaStyle,
    ReportMeta,
    Run,
    TableBlock,
    TableColumn,
    clean_text,
    runs_of,
)

DEFAULT_HEADING = "Annexure — Questionnaire responses"

# A runaway form must not turn a report into a document nobody can open, for the same reason
# ``report_annexures.MAX_PARAGRAPHS_PER_TRANSCRIPT`` exists: both renderers lay out every row before
# the designer sees a page. Forty sittings of four hundred answers is far above any real instrument
# — the pro-forma parser itself caps a workbook well below this — and far below the size at which the
# render stops finishing. Truncation SAYS SO in the document rather than dropping the tail silently,
# because half a respondent's answers that nobody is told about is worse than a visible note.
MAX_SITTINGS_PER_QUESTIONNAIRE = 40
MAX_ROWS_PER_SITTING = 400

#: What an unanswered REQUIRED question prints. The report's editorial rule, stated in
#: ``report_builder``'s module docstring: an empty OPTIONAL field prints nothing, an empty REQUIRED
#: one prints this, so a gap in the record is visible as a gap rather than as an absence. A sitting
#: that skipped a required question is a fact about the fieldwork and belongs in the evidence.
NOT_RECORDED = "Not recorded."

#: Marks a question that was reworded after this answer was given. The answer stays attached to the
#: wording it was recorded under (see ``QuestionnaireFormQuestion.supersededById``); printing it
#: without saying so would present a retired prompt as the question still being asked.
RETIRED_NOTE = "no longer asked"


# --------------------------------------------------------------------------------------
# One questionnaire, as every surface reads it
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class QuestionnaireAnswer:
    """One question and what one sitting answered to it, already resolved from the database.

    ``prompt`` is the wording the answer was GIVEN UNDER, never the current wording of a question
    that superseded it — that distinction is the whole point of the supersede rule and losing it here
    would put the rule's own failure case back into a ministry report.
    """

    prompt: str
    answer_text: str = ""
    notes: str = ""
    section_code: str = ""
    section_title: str = ""
    is_required: bool = False
    is_retired: bool = False

    @property
    def has_answer(self) -> bool:
        return bool(self.answer_text and self.answer_text.strip())

    @property
    def prints(self) -> bool:
        """Whether this row belongs in the document at all.

        An answered question always prints. An unanswered one prints only when it was REQUIRED,
        where the blank is itself the finding. An unanswered optional question prints nothing, so a
        forty-question form answered on eight questions is eight rows rather than forty.
        """
        return self.has_answer or self.is_required

    @property
    def section_label(self) -> str:
        return " — ".join(p for p in (self.section_code, self.section_title) if p)


@dataclass(frozen=True, slots=True)
class QuestionnaireSitting:
    """ONE FILLED-IN COPY of a questionnaire — one respondent, one sitting.

    ``source`` is ``APP`` or ``UPLOAD``: answers that arrived already filled in inside the uploaded
    workbook are the same rows as answers typed in the app, and the annexure says which is which
    because an officer reading a verbatim answer is entitled to know whether it was recorded in front
    of the respondent or transcribed into a spreadsheet afterwards.
    """

    entry_id: str
    title: str = ""
    respondent_name: str = ""
    source: str = ""
    notes: str = ""
    recorded_at: str = ""
    recorded_by: str = ""
    answers: tuple[QuestionnaireAnswer, ...] = ()

    @property
    def label(self) -> str:
        """How the sitting is titled in the annexure.

        The respondent first, because that is who the answers are FROM; the sitting's own title only
        when no respondent was named. A heading reading "Entry 3" tells a reader nothing about whose
        answers they are about to read.
        """
        return (self.respondent_name.strip() or self.title.strip()
                or f"Sitting {self.entry_id[:8]}")

    @property
    def printed_answers(self) -> tuple[QuestionnaireAnswer, ...]:
        return tuple(a for a in self.answers if a.prints)

    @property
    def answered_count(self) -> int:
        return sum(1 for a in self.answers if a.has_answer)

    @property
    def has_answers(self) -> bool:
        return self.answered_count > 0


@dataclass(frozen=True, slots=True)
class QuestionnaireItem:
    """One custom questionnaire attached to this workshop, with every sitting recorded against it."""

    questionnaire_id: str
    title: str = ""
    description: str = ""
    version: int = 1
    source_filename: str = ""
    question_count: int = 0
    sittings: tuple[QuestionnaireSitting, ...] = ()

    @property
    def printed_sittings(self) -> tuple[QuestionnaireSitting, ...]:
        """The sittings this annexure prints: the ones that actually carry an answer.

        A sitting created and never filled in is an empty page in an appendix of evidence. It is
        reported as a warning beside the download instead — see :func:`questionnaire_warnings` — so
        the designer hears about it rather than finding a blank heading in sixty pages.
        """
        return tuple(s for s in self.sittings if s.has_answers)

    @property
    def has_answers(self) -> bool:
        return bool(self.printed_sittings)

    @property
    def answered_total(self) -> int:
        return sum(s.answered_count for s in self.printed_sittings)


# --------------------------------------------------------------------------------------
# Carrying the questionnaires to the renderer
# --------------------------------------------------------------------------------------

# The attribute the items travel on, exactly as ``report_annexures._ATTR`` carries the transcripts
# and for the same reason: ``WorkshopData`` is a plain dataclass in report_builder.py, the two halves
# below are the only code that touches the name, and a feature that can be wired without editing a
# shared dataclass should be.
_ATTR = "questionnaires"


def attach_questionnaires(
    data: Any, items: list[QuestionnaireItem] | tuple[QuestionnaireItem, ...]
) -> Any:
    """Put the loaded questionnaires on the workshop data the builder will walk. Returns ``data``."""
    try:
        setattr(data, _ATTR, tuple(items))
    except AttributeError:
        # A ``slots`` dataclass would refuse the attribute. Losing the annexure is the right failure:
        # a report missing an appendix is still a report, and raising here would take away a
        # designer's ability to generate anything at all over an optional extra.
        return data
    return data


def questionnaires_of(data: Any) -> tuple[QuestionnaireItem, ...]:
    """The questionnaires attached to ``data``, or none. Safe on any workshop data object."""
    return tuple(getattr(data, _ATTR, ()) or ())


# --------------------------------------------------------------------------------------
# The blocks
# --------------------------------------------------------------------------------------


def _question_runs(answer: QuestionnaireAnswer) -> tuple[Run, ...]:
    """The question cell: the prompt, and the retirement marker when there is one."""
    runs = list(runs_of(answer.prompt or "—"))
    if answer.is_retired:
        runs.extend(runs_of(f" ({RETIRED_NOTE})", italic=True))
    return tuple(runs)


def _answer_runs(answer: QuestionnaireAnswer) -> tuple[Run, ...]:
    """The answer cell: what was said, and the interviewer's note under it when there is one.

    The note is italic and prefixed rather than run together with the answer, because a note is the
    interviewer's own words about the answer and an officer quoting the cell must be able to tell the
    two apart. It is one cell rather than a third column so the table still fits a portrait page when
    the answers are sentences, which they are.
    """
    if not answer.has_answer:
        return runs_of(NOT_RECORDED, italic=True)
    runs = list(runs_of(answer.answer_text.strip()))
    note = (answer.notes or "").strip()
    if note:
        runs.extend(runs_of(f"  Note: {note}", italic=True))
    return tuple(runs)


def _sitting_provenance(sitting: QuestionnaireSitting) -> str:
    """The one NOTE line under a sitting's heading saying where its answers came from.

    THE COUNT IS BARE AND NOT "X OF Y", deliberately. A sitting can carry answers to questions the
    form no longer asks — a reworded question keeps its old answers under its old wording, which is
    the supersede rule doing its job — so an answered count can legitimately exceed the number of
    questions the questionnaire currently contains. "9 of 8 questions answered" in a document
    submitted to a ministry reads as a defect in the app rather than as the truth about the record.
    """
    source = {"UPLOAD": "recorded on the uploaded spreadsheet",
              "APP": "recorded in the app"}.get(str(sitting.source).upper(), "")
    parts = [p for p in (
        f"{sitting.answered_count} question(s) answered",
        source,
        sitting.recorded_at[:10] if sitting.recorded_at else "",
        f"recorded by {sitting.recorded_by}" if sitting.recorded_by else "",
    ) if p]
    return " · ".join(parts)


def _questionnaire_provenance(item: QuestionnaireItem) -> str:
    """The line under a questionnaire's heading that lets a file be matched back to its source.

    The id and the VERSION are both here on purpose. ``Questionnaire.version`` counts the
    destructive-ish edits applied after the first answer existed — a supersede or a retire — so two
    reports of the same workshop that disagree can be told apart by it without opening the database.
    """
    printed = item.printed_sittings
    parts = [p for p in (
        f"{len(printed)} sitting(s)",
        f"{item.question_count} question(s)" if item.question_count else "",
        f"version {item.version}",
        f"from {item.source_filename}" if item.source_filename else "",
        f"questionnaire {item.questionnaire_id}",
    ) if p]
    return ("The designer's own questionnaire, attached to this workshop · "
            + " · ".join(parts))


def questionnaire_index_block(items: tuple[QuestionnaireItem, ...]) -> TableBlock:
    """The contents table at the head of the annexure — what was asked, and how much came back."""
    return TableBlock(
        columns=(
            TableColumn("Questionnaire", 46.0),
            TableColumn("Questions", 16.0, numeric=True),
            TableColumn("Sittings", 16.0, numeric=True),
            TableColumn("Answers recorded", 22.0, numeric=True),
        ),
        rows=tuple(
            (
                runs_of(item.title or "Untitled questionnaire"),
                runs_of(f"{item.question_count:,}" if item.question_count else "—"),
                runs_of(f"{len(item.printed_sittings):,}"),
                runs_of(f"{item.answered_total:,}"),
            )
            for item in items
        ),
        caption="Questionnaires attached to this workshop and the sittings recorded against them.",
    )


def sitting_blocks(sitting: QuestionnaireSitting) -> list[Block]:
    """One sitting's answers as report blocks: a labelled table per section of the form.

    ONE TABLE PER SECTION, EACH PRECEDED BY ITS LABEL, and the label is not only editorial. Both
    writers refuse two adjacent tables — an empty paragraph between them is what stops Word merging
    the two into one — so a per-section table with nothing in between would either merge the sections
    or need a blank paragraph that says nothing. The section label is the paragraph that has to be
    there anyway, doing the job.
    """
    blocks: list[Block] = []
    rows: list[tuple[Any, ...]] = []
    current = ""
    printed = 0
    truncated = False

    def flush() -> None:
        if not rows:
            return
        if current:
            blocks.append(ParagraphBlock(runs=runs_of(current, bold=True), style=ParaStyle.BODY,
                                         align=Align.LEFT))
        blocks.append(TableBlock(
            columns=(TableColumn("Question", 46.0), TableColumn("Answer", 54.0)),
            rows=tuple(rows),
        ))
        rows.clear()

    for answer in sitting.printed_answers:
        if printed >= MAX_ROWS_PER_SITTING:
            truncated = True
            break
        label = answer.section_label
        if label != current:
            flush()
            current = label
        rows.append((_question_runs(answer), _answer_runs(answer)))
        printed += 1
    flush()

    if truncated:
        blocks.append(ParagraphBlock(
            runs=runs_of(
                f"[Answers truncated after {MAX_ROWS_PER_SITTING} questions. The full set is held "
                f"against the questionnaire in the repository.]"
            ),
            style=ParaStyle.NOTE,
        ))
    return blocks


def append_questionnaire_annexure(
    doc: DocumentBuilder,
    items: tuple[QuestionnaireItem, ...] | list[QuestionnaireItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> int:
    """Append the whole annexure to ``doc``. Returns how many questionnaires were printed.

    THE ONE CALL SITE is ``report_builder.ReportBuilder.build``, in the ``if/elif`` chain over
    ``section.special``, beside the ``ANNEXURE_TRANSCRIPTS`` branch. Keep it the only one: two callers
    would mean two chances to pass a different heading or a different ``numbered``, and a report whose
    annexure is numbered on the phone and unnumbered at the office is exactly the divergence this
    pipeline exists to end.

    With nothing attached — no questionnaire on this workshop, or none of them answered — this
    appends nothing at all, not even the page break, so every existing template renders byte-for-byte
    as it did.

    Headings go through ``doc.heading`` rather than being constructed here because the "3.2" counters
    and the Word bookmarks are the DocumentBuilder's to maintain; a heading built by hand would be
    missing from the table of contents and unclickable in the .docx.
    """
    printed = [item for item in items if item.has_answers]
    if not printed:
        return 0
    if page_break_before:
        doc.add(PageBreakBlock())
    doc.heading(heading or DEFAULT_HEADING, 1, numbered=numbered)
    doc.para(
        "The questionnaires this workshop's designer built and attached to it, and every sitting "
        "recorded against them. Each question is printed in the wording the answer was given under: "
        "a question reworded after it was answered is shown here as it was asked, marked "
        f"“{RETIRED_NOTE}”. A required question left blank is printed as “{NOT_RECORDED}” so that a "
        "gap in the fieldwork is visible as a gap.",
        style=ParaStyle.LEAD,
    )
    doc.add(questionnaire_index_block(tuple(printed)))
    for item in printed:
        doc.heading(item.title or "Untitled questionnaire", 2, numbered=numbered)
        doc.para(_questionnaire_provenance(item), style=ParaStyle.NOTE)
        doc.para(item.description)
        sittings = item.printed_sittings
        dropped = len(sittings) - MAX_SITTINGS_PER_QUESTIONNAIRE
        for sitting in sittings[:MAX_SITTINGS_PER_QUESTIONNAIRE]:
            doc.heading(sitting.label, 3, numbered=numbered)
            doc.para(_sitting_provenance(sitting), style=ParaStyle.NOTE)
            doc.para(sitting.notes)
            for block in sitting_blocks(sitting):
                doc.add(block)
        if dropped > 0:
            doc.para(
                f"[{dropped} further sitting(s) were recorded against this questionnaire and are "
                f"not printed here. The full set is held in the repository.]",
                style=ParaStyle.NOTE,
            )
    return len(printed)


def questionnaire_annexure_blocks(
    items: tuple[QuestionnaireItem, ...] | list[QuestionnaireItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> tuple[Block, ...]:
    """The annexure as a plain block tuple, built in isolation.

    For tests, for the web preview, and for any caller that wants the blocks without a document
    around them. The heading numbers restart at 1 because nothing else has been counted, which is why
    the real report goes through :func:`append_questionnaire_annexure` instead.
    """
    scratch = DocumentBuilder(meta=ReportMeta(title=""))
    append_questionnaire_annexure(
        scratch, items, heading=heading, numbered=numbered,
        page_break_before=page_break_before,
    )
    return scratch.build().blocks


def questionnaire_warnings(
    items: tuple[QuestionnaireItem, ...] | list[QuestionnaireItem]
) -> list[str]:
    """What the designer should be told about the questionnaires attached to this workshop.

    A questionnaire with no answered sitting is silently absent from the annexure, and silence is
    exactly wrong here: the designer attached the form to this workshop on purpose and would
    otherwise have to notice the shortfall themselves in a sixty-page document. These reach the
    ``X-Report-Warnings`` header beside the download, exactly as the transcript annexure's do.
    """
    empty = [item for item in items if not item.has_answers]
    if not empty:
        return []
    names = ", ".join(clean_text(item.title) or item.questionnaire_id for item in empty[:3])
    more = "…" if len(empty) > 3 else ""
    return [
        f"{len(empty)} questionnaire(s) attached to this workshop have no recorded answers and "
        f"were left out of the questionnaire annexure ({names}{more})."
    ]
