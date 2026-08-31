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

from app.services import rich_text
from app.services.questionnaire_kinds import label_for, stage_key_for
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
    #: EXACTLY WHAT THE COLUMN HOLDS, UNFLATTENED — see :attr:`plain_answer`, which is what every
    #: reader below goes through instead. The loader (``questionnaire_forms.report_items``) hands
    #: ``QuestionnaireFormAnswer.answerText`` over as stored, and keeping it raw here means the
    #: flattening happens ONCE, at the one place that renders it, rather than inside a loader whose
    #: other callers want the stored value.
    answer_text: str = ""
    notes: str = ""
    section_code: str = ""
    section_title: str = ""
    is_required: bool = False
    is_retired: bool = False

    @property
    def plain_answer(self) -> str:
        """The answer as the words a person wrote — a stored document flattened, prose untouched.

        **THIS IS THE RICH-TEXT READ BOUNDARY FOR THE ANNEXURE.** ``rich_text``'s "rich text inside
        a plain ``String`` column" section describes the encoding this defends against: a ``String?``
        column that holds NULL, prose, or a whole serialised document as ``{"blocks":[…]}``, told
        apart only by looking at the value. Without this call the raw value went to ``runs_of``,
        which stringifies whatever it is given — so a stored document printed as the literal text
        ``{"blocks":[{"kind":"PARAGRAPH",…}]}`` into a document submitted to a ministry, AND
        ``has_answer`` below read that JSON-shaped string as a filled field, so no emptiness check
        anywhere reported a problem. That is the failure ``report_builder.format_value`` records at
        its own RICH_TEXT branch, one table over, in as many words.

        ── WHICH COLUMN THIS ACTUALLY IS, SAID PLAINLY, BECAUSE THE TWO ARE EASY TO CONFUSE ────────
        This annexure renders ``QuestionnaireFormAnswer.answerText`` — the DESIGNER'S OWN form, the
        ``Questionnaire``/``QuestionnaireForm*`` family attached to a workshop from a dropdown. It is
        NOT ``QuestionnaireResponse.answerText``, the ministry interview instrument on
        ``/questionnaire`` (singular), which is the box that became a ``RichTextField`` on
        2026-08-31 and the one that demonstrably stores documents today. As of that date no client
        writes a document into THIS column: the plural form builder's answer box is a plain field on
        both the web and the handset, and the uploaded-workbook path stores what the spreadsheet
        held.

        So this is a guard at a render boundary rather than a fix for an observed corrupt row, and it
        is here on three grounds. The column is a free ``String?`` that any client may post any
        string into, so "no client does" is a fact about today rather than a property of the schema.
        The two columns are one feature away from converging — the two answer boxes are the same
        control in a designer's head, and the sibling was promoted without this module hearing about
        it. And the cost of being wrong is not symmetric: everywhere else a JSON-shaped cell is an
        ugly row somebody can re-export, and here it is a paragraph inside a .docx already handed to
        an officer. The guard costs one identity call per answer.

        ``plain_from_stored`` AND NOT ``from_json``/``to_plain``: this value came out of a ``String``
        column, and ``from_json`` reads a ``str`` as PROSE by design, so it would hand back a
        paragraph of JSON syntax rather than the answer. The stored-column front door is the only one
        that tries JSON first. It also returns a plain string BY IDENTITY — not by a round trip that
        re-strips and re-spaces — so every answer in the existing corpus renders byte-for-byte as it
        did before this property existed.
        """
        return str(rich_text.plain_from_stored(self.answer_text or "")).strip()

    @property
    def has_answer(self) -> bool:
        """Whether this sitting actually answered the question.

        Measured on the FLATTENED text, which is what makes an empty document count as empty: a
        rich-text box that was focused and left alone still saves
        ``{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}``, and a raw ``.strip()`` on that string is
        truthy. It would have printed an empty answer cell, counted towards ``answered_count`` on the
        sitting's provenance line and towards "Answers recorded" in the index table — three
        statements about fieldwork that never happened. ``rich_text.is_empty`` is not the right test
        here for the same reason ``from_json`` is not: it reads a ``str`` as prose and would call
        that value filled.
        """
        return bool(self.plain_answer)

    @property
    def prints(self) -> bool:
        """Whether this row belongs in the document at all.

        An answered question always prints. An unanswered one prints only when it was REQUIRED AND
        STILL ASKED, where the blank is itself the finding. An unanswered optional question prints
        nothing, so a forty-question form answered on eight questions is eight rows rather than
        forty.

        **THE ``not self.is_retired`` HALF IS LOAD-BEARING AND WAS MISSING.** ``prints`` used to be
        ``has_answer or is_required``, which never consulted retirement — and nothing upstream clears
        ``isRequired`` when a question is retired or superseded (``questionnaire_forms.supersede_question``
        deliberately COPIES it onto the replacement and leaves it set on the original, and the plain
        retire path writes only ``isActive``/``retiredAt``). So a required question that was reworded
        stayed permanently ``is_required=True, has_answer=False, is_retired=True`` for every sitting
        that never saw the old wording, and each of those sittings' tables carried an extra
        "… (no longer asked) | Not recorded." row. Twenty sittings recorded after one rewording is
        twenty rows asserting that a respondent left blank a question they were never shown, in a
        document whose stated editorial rule (see ``NOT_RECORDED`` above) is that a blank means a gap
        in the FIELDWORK. It also made ``_questionnaire_provenance`` read "8 question(s)" over eleven
        rows, because ``question_count`` counts only live questions, and it spent the
        ``MAX_ROWS_PER_SITTING`` budget on rows nobody asked for.

        This is the rule its own loader already states — questionnaire_forms.report_items: "Retired
        ones are printed where they carry an answer but are not counted here, because this number is
        what a reader compares against the instrument, and the instrument no longer contains them" —
        and it is exactly what the sibling module does for a designer's custom fields
        (``report_custom_sections.CustomSectionItem.printed_fields``: ``not f.retired or _has_answer(...)``).
        An answered retired question is untouched by this: ``has_answer`` short-circuits first, so it
        still prints under the wording it was given, marked "(no longer asked)". Do not "simplify"
        this back to two terms — the third one is the only thing standing between a reworded required
        question and a false gap in every sitting recorded afterwards.
        """
        return self.has_answer or (self.is_required and not self.is_retired)

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
        return self.respondent_name.strip() or self.title.strip() or f"Sitting {self.entry_id[:8]}"

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
    #: ``Questionnaire.kind`` — ``WORKSHOP_INTERVIEW``, ``MARKET_SURVEY``, or ``""`` for a form
    #: whose designer has not said. It decides which STAGE this instrument's answers are filed under
    #: in the annexure below; the vocabulary and the mapping are
    #: :mod:`app.services.questionnaire_kinds`, which is also where the argument for each stage is.
    #:
    #: A PLAIN STRING AND NOT AN ENUM, matching every other field on this dataclass: the renderer
    #: must be able to print a report from a row written by a NEWER deployment carrying a token this
    #: build has never heard of, and an enum would raise on the way in and take the whole document
    #: with it. An unknown token groups under itself and prints its own name — see
    #: :func:`_stage_group`.
    kind: str = ""
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
    # A FORMATTED ANSWER KEEPS ITS MARKS AND A PLAIN ONE IS UNTOUCHED, and the branch is what makes
    # the second half true. ``plain_runs`` is the in-a-table-cell renderer — the same one
    # ``report_builder._cell_runs`` reaches for, because a cell holds runs and cannot hold a block —
    # so bold and italics survive and paragraph breaks collapse to single spaces, which is the trade
    # that module documents. It is NOT applied to prose: ``plain_runs`` would push an ordinary
    # multi-line answer through ``from_plain``/block-join and re-space it, silently reformatting
    # every answer in the existing corpus to fix a problem those rows do not have.
    document = rich_text.stored_text_document(answer.answer_text or "")
    runs = list(runs_of(answer.plain_answer) if document is None else rich_text.plain_runs(document))
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
    source = {"UPLOAD": "recorded on the uploaded spreadsheet", "APP": "recorded in the app"}.get(
        str(sitting.source).upper(), ""
    )
    parts = [
        p
        for p in (
            f"{sitting.answered_count} question(s) answered",
            source,
            sitting.recorded_at[:10] if sitting.recorded_at else "",
            f"recorded by {sitting.recorded_by}" if sitting.recorded_by else "",
        )
        if p
    ]
    return " · ".join(parts)


def _questionnaire_provenance(item: QuestionnaireItem) -> str:
    """The line under a questionnaire's heading that lets a file be matched back to its source.

    The id and the VERSION are both here on purpose. ``Questionnaire.version`` counts the
    destructive-ish edits applied after the first answer existed — a supersede or a retire — so two
    reports of the same workshop that disagree can be told apart by it without opening the database.
    """
    printed = item.printed_sittings
    parts = [
        p
        for p in (
            # THE KIND AND ITS STAGE, FIRST, because it is the one clause that says what the reader
            # is looking at rather than how much of it there is. Empty for an unstated kind, which
            # keeps the line byte-identical to what it was for every questionnaire recorded before
            # the column existed — the same rule the grouping above follows.
            _stage_group(item)[2],
            f"{len(printed)} sitting(s)",
            f"{item.question_count} question(s)" if item.question_count else "",
            f"version {item.version}",
            f"from {item.source_filename}" if item.source_filename else "",
            f"questionnaire {item.questionnaire_id}",
        )
        if p
    ]
    return "The designer's own questionnaire, attached to this workshop · " + " · ".join(parts)


# --------------------------------------------------------------------------------------
# Which stage each questionnaire belongs to
# --------------------------------------------------------------------------------------
#
# THE OWNER'S SENTENCE, 2026-08-30: *"they also do market survey interviews, so create that
# differentiation as well, so that we can map the questionnaires and the transcripts to the correct
# stage in the report."* This is the half of it that happens in the report: the annexure used to be
# one flat list, so a baseline interview and a market survey printed under one heading, in creation
# order, with nothing on the page saying which part of the workshop either was evidence for. An
# officer reading it could not tell an artisan's answers from a shopkeeper's.
#
# GROUPED, NOT MOVED. The questionnaires stay in the ANNEXURE rather than being printed inside the
# stage bodies, and that is deliberate — this module's own header argues it at length: a set of
# sittings is unbounded in length and is read as proof rather than as narrative, and the stage bodies
# already print stage 7's typed question list and stage 8's `surveyResponse` rows, so a third
# structured answer table between them makes a reader ask which of the three is "the survey". What
# the kind changes is which stage each group is LABELLED with, and the order the groups appear in,
# which is what makes the annexure navigable back to the stage it supports.

# Stage number and title per stage key, for the group headings. Read from the stage registry rather
# than typed here, so a renamed stage renames its group heading too and cannot leave this module
# printing a title the rest of the report has stopped using.
def _stage_titles() -> dict[str, tuple[int, str]]:
    """``{stage key: (number, title)}``, read through ``stage_schema.stages()``.

    ``stages()`` AND NOT AN IMPORTED CONSTANT, which is not a style preference — that function's own
    docstring records the outage it exists to prevent. ``stage_schema.STAGES`` is an empty tuple at
    import time and is REBOUND by ``_install`` when ``stage_definitions`` loads, so
    ``from app.services.stage_schema import STAGES`` captures the empty copy for the life of the
    process; under uvicorn that produced a server answering ``stages: 0`` while every test that
    imported the module was green. ``stage_definitions.ALL_STAGES`` would work but is the
    declaration site rather than the registry, and reaching past the accessor is how the next
    reader learns the wrong habit.

    LAZILY, and this is the one import in this module that is not at the top: loading the registry
    builds all 22 stages, and this module is imported by ``questionnaire_forms``, which is imported
    by routes that have nothing to do with reports. By the time a report is generated the registry is
    already in memory, so this costs a dict comprehension on the path that uses it and nothing on the
    paths that do not.
    """
    from app.services.stage_schema import stages

    return {stage.key: (stage.number, stage.title) for stage in stages()}


def _stage_group(item: QuestionnaireItem) -> tuple[int, str, str]:
    """``(sort key, group heading, provenance clause)`` for one questionnaire.

    THREE CASES, AND THE THIRD ONE IS WHY THIS RETURNS A SORT KEY RATHER THAN A STAGE NUMBER:

    * a KNOWN kind mapped to a KNOWN stage sorts by that stage's number, so the groups appear in the
      order the workshop happened — stage 6's interviews before stage 8's market survey — which is
      the order the body of the report is already in;
    * a kind this build does not recognise (a row written by a newer deployment) sorts after every
      real stage and is labelled with the token itself. It is NOT silently folded into "not stated":
      somebody chose that value, and relabelling their choice as an absence is the one wrong answer
      available;
    * NO KIND AT ALL sorts LAST and gets no stage heading. Every questionnaire that existed before
      the ``kind`` column did is in this state, and there is no backfill by design (see the column's
      comment in ``schema.prisma``), so this is the case that keeps already-generated reports
      reproducible.
    """
    if not item.kind:
        return (10_000, "", "")
    stage_key = stage_key_for(item.kind)
    label = label_for(item.kind)
    if not stage_key:
        return (9_000, label, f"filed as {label}")
    number, title = _stage_titles().get(stage_key, (8_000, stage_key))
    return (number, f"{label} \u2014 stage {number}, {title}", f"filed as {label}, under stage {number} ({title})")


def grouped_by_stage(
    items: tuple[QuestionnaireItem, ...] | list[QuestionnaireItem],
) -> list[tuple[str, list[QuestionnaireItem]]]:
    """The questionnaires as ``[(group heading, items)]``, in stage order.

    ``""`` IS A REAL HEADING AND MEANS "PRINT THESE WITHOUT ONE". A report whose questionnaires all
    predate the ``kind`` column therefore renders EXACTLY as it did before this grouping existed —
    one heading, one index table, the questionnaires in creation order — which is what makes this
    change safe to apply to a repository full of unstated rows. The moment a designer states a kind,
    that form lifts out into its own labelled group and the unstated remainder keeps its old shape at
    the bottom.

    Stable within a group: ``sorted`` is stable, so ``report_items``' ``createdAt asc`` ordering
    survives inside each stage.
    """
    keyed = sorted(items, key=lambda item: _stage_group(item)[0])
    groups: list[tuple[str, list[QuestionnaireItem]]] = []
    for item in keyed:
        heading = _stage_group(item)[1]
        if groups and groups[-1][0] == heading:
            groups[-1][1].append(item)
        else:
            groups.append((heading, [item]))
    return groups


def questionnaire_index_block(items: tuple[QuestionnaireItem, ...]) -> TableBlock:
    """The contents table at the head of the annexure — what was asked, and how much came back."""
    # THE KIND IS A COLUMN HERE AND A HEADING BELOW, which is not a duplication: the index is the
    # one place a reader sees every questionnaire at once, and "which of these is the market survey"
    # is the question it exists to answer. The widths are re-cut from the four the table had rather
    # than squeezed, and still total 100.
    return TableBlock(
        columns=(
            TableColumn("Questionnaire", 36.0),
            TableColumn("Kind", 18.0),
            TableColumn("Questions", 14.0, numeric=True),
            TableColumn("Sittings", 14.0, numeric=True),
            TableColumn("Answers recorded", 18.0, numeric=True),
        ),
        rows=tuple(
            (
                runs_of(item.title or "Untitled questionnaire"),
                # ``label_for`` answers "Kind not stated" for an empty value rather than leaving the
                # cell blank, because a blank cell in a printed table reads as data that failed to
                # load rather than as a question nobody answered.
                runs_of(label_for(item.kind)),
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
            blocks.append(
                ParagraphBlock(
                    runs=runs_of(current, bold=True), style=ParaStyle.BODY, align=Align.LEFT
                )
            )
        blocks.append(
            TableBlock(
                columns=(TableColumn("Question", 46.0), TableColumn("Answer", 54.0)),
                rows=tuple(rows),
            )
        )
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
        blocks.append(
            ParagraphBlock(
                runs=runs_of(
                    f"[Answers truncated after {MAX_ROWS_PER_SITTING} questions. The full set is held "
                    f"against the questionnaire in the repository.]"
                ),
                style=ParaStyle.NOTE,
            )
        )
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
    # ══ GROUPED BY THE STAGE EACH KIND BELONGS TO ═══════════════════════════════════════════════
    #
    # ONE EXTRA HEADING LEVEL, AND ONLY WHERE A KIND WAS STATED. A group whose heading is empty —
    # which is every questionnaire on every workshop that predates the ``kind`` column — prints its
    # questionnaires at heading level 2 exactly as this function always did, so a report generated
    # from unstated rows is byte-for-byte the report it was. A stated group takes level 2 for the
    # stage and pushes its questionnaires to level 3, and their sittings to 4.
    #
    # THE DEPTHS ARE COMPUTED RATHER THAN WRITTEN TWICE, because a heading level typed in two
    # branches is the kind of thing that drifts by one and produces a table of contents where a
    # sitting outranks the questionnaire it belongs to.
    for stage_heading, group in grouped_by_stage(printed):
        if stage_heading:
            doc.heading(stage_heading, 2, numbered=numbered)
        depth = 3 if stage_heading else 2
        for item in group:
            doc.heading(item.title or "Untitled questionnaire", depth, numbered=numbered)
            doc.para(_questionnaire_provenance(item), style=ParaStyle.NOTE)
            doc.para(item.description)
            sittings = item.printed_sittings
            dropped = len(sittings) - MAX_SITTINGS_PER_QUESTIONNAIRE
            for sitting in sittings[:MAX_SITTINGS_PER_QUESTIONNAIRE]:
                doc.heading(sitting.label, depth + 1, numbered=numbered)
                doc.para(_sitting_provenance(sitting), style=ParaStyle.NOTE)
                doc.para(sitting.notes)
                for block in sitting_blocks(sitting):
                    doc.add(block)
            if dropped > 0:
                doc.para(
                    f"[{dropped} further sitting(s) were recorded against this questionnaire and "
                    f"are not printed here. The full set is held in the repository.]",
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
        scratch,
        items,
        heading=heading,
        numbered=numbered,
        page_break_before=page_break_before,
    )
    return scratch.build().blocks


def questionnaire_warnings(
    items: tuple[QuestionnaireItem, ...] | list[QuestionnaireItem],
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
