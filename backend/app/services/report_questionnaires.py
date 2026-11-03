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
from app.services.text_format import by_count, count_noun

DEFAULT_HEADING = "Annexure — Questionnaire responses"

MAX_SITTINGS_PER_QUESTIONNAIRE = 40
MAX_ROWS_PER_SITTING = 400

NOT_RECORDED = "Not recorded."

RETIRED_NOTE = "no longer asked"



@dataclass(frozen=True, slots=True)
class QuestionnaireAnswer:
    prompt: str
    answer_text: str = ""
    notes: str = ""
    section_code: str = ""
    section_title: str = ""
    is_required: bool = False
    is_retired: bool = False

    @property
    def plain_answer(self) -> str:
        return str(rich_text.plain_from_stored(self.answer_text or "")).strip()

    @property
    def has_answer(self) -> bool:
        return bool(self.plain_answer)

    @property
    def prints(self) -> bool:
        return self.has_answer or (self.is_required and not self.is_retired)

    @property
    def section_label(self) -> str:
        return " — ".join(p for p in (self.section_code, self.section_title) if p)


@dataclass(frozen=True, slots=True)
class QuestionnaireSitting:
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
    questionnaire_id: str
    title: str = ""
    description: str = ""
    version: int = 1
    source_filename: str = ""
    kind: str = ""
    question_count: int = 0
    sittings: tuple[QuestionnaireSitting, ...] = ()

    @property
    def printed_sittings(self) -> tuple[QuestionnaireSitting, ...]:
        return tuple(s for s in self.sittings if s.has_answers)

    @property
    def has_answers(self) -> bool:
        return bool(self.printed_sittings)

    @property
    def answered_total(self) -> int:
        return sum(s.answered_count for s in self.printed_sittings)


_ATTR = "questionnaires"


def attach_questionnaires(
    data: Any, items: list[QuestionnaireItem] | tuple[QuestionnaireItem, ...]
) -> Any:
    try:
        setattr(data, _ATTR, tuple(items))
    except AttributeError:
        return data
    return data


def questionnaires_of(data: Any) -> tuple[QuestionnaireItem, ...]:
    return tuple(getattr(data, _ATTR, ()) or ())



def _question_runs(answer: QuestionnaireAnswer) -> tuple[Run, ...]:
    runs = list(runs_of(answer.prompt or "—"))
    if answer.is_retired:
        runs.extend(runs_of(f" ({RETIRED_NOTE})", italic=True))
    return tuple(runs)


def _answer_runs(answer: QuestionnaireAnswer) -> tuple[Run, ...]:
    if not answer.has_answer:
        return runs_of(NOT_RECORDED, italic=True)
    document = rich_text.stored_text_document(answer.answer_text or "")
    runs = list(runs_of(answer.plain_answer) if document is None else rich_text.plain_runs(document))
    note = (answer.notes or "").strip()
    if note:
        runs.extend(runs_of(f"  Note: {note}", italic=True))
    return tuple(runs)


def _sitting_provenance(sitting: QuestionnaireSitting) -> str:
    source = {"UPLOAD": "recorded on the uploaded spreadsheet", "APP": "recorded in the app"}.get(
        str(sitting.source).upper(), ""
    )
    parts = [
        p
        for p in (
            f"{count_noun(sitting.answered_count, 'question')} answered",
            source,
            sitting.recorded_at[:10] if sitting.recorded_at else "",
            f"recorded by {sitting.recorded_by}" if sitting.recorded_by else "",
        )
        if p
    ]
    return " · ".join(parts)


def _questionnaire_provenance(item: QuestionnaireItem) -> str:
    printed = item.printed_sittings
    parts = [
        p
        for p in (
            _stage_group(item)[2],
            count_noun(len(printed), "sitting"),
            count_noun(item.question_count, "question") if item.question_count else "",
            f"version {item.version}",
            f"from {item.source_filename}" if item.source_filename else "",
            f"questionnaire {item.questionnaire_id}",
        )
        if p
    ]
    return "The designer's own questionnaire, attached to this workshop · " + " · ".join(parts)


def _stage_titles() -> dict[str, tuple[int, str]]:
    from app.services.stage_schema import stages

    return {stage.key: (stage.number, stage.title) for stage in stages()}


def _stage_group(item: QuestionnaireItem) -> tuple[int, str, str]:
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
                    f"[Answers truncated after {MAX_ROWS_PER_SITTING} questions. The full set is in "
                    f"the online workshop record.]"
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
                    f"[{dropped} further {by_count(dropped, 'sitting was', 'sittings were')} "
                    f"recorded against this questionnaire and {by_count(dropped, 'is', 'are')} not "
                    f"printed here. The full set is in the online workshop record.]",
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
    empty = [item for item in items if not item.has_answers]
    if not empty:
        return []
    names = ", ".join(clean_text(item.title) or item.questionnaire_id for item in empty[:3])
    more = "…" if len(empty) > 3 else ""
    return [
        f"{count_noun(len(empty), 'questionnaire')} attached to this workshop "
        f"{by_count(len(empty), 'has', 'have')} no recorded answers and "
        f"{by_count(len(empty), 'was', 'were')} left out of the questionnaire annexure ({names}{more})."
    ]
