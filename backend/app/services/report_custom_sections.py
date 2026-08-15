"""A designer's own questions, printed in the report where they were asked.

Step 6 of ``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §5. The rules a custom section obeys are in
:mod:`app.services.custom_sections`; what matters HERE is that a designer who added a question and
answered it finds the answer in the document, under a heading that says where it came from.

**A MODULE WITH NO CALL SITE IS NOT A FEATURE**, which is the sentence
:mod:`app.services.report_ai_layers` was written under and the reason it is repeated: the transcript
annexure was a complete, tested module with no branch in ``ReportBuilder.build``, so every report
ever generated dropped it in silence while three surfaces told the designer the office's copy would
carry it. This module is reached from exactly one branch of that chain, and
``report_templates.apply_report_settings`` is the only thing that puts the section in front of it.

**WHY IT IS ITS OWN MODULE AND NOT A BRANCH INSIDE THE BUILDER.**
:mod:`app.services.report_builder` is the generic template interpreter and is deliberately free of
per-feature code — a stage is printed by walking its ``EntitySpec``s and dispatching on each field's
``ReportRole``, with no per-stage code anywhere. Custom fields have no ``EntitySpec`` and no
``ReportRole``, so they need their own small renderer; giving it a file of its own is what keeps the
interpreter generic and keeps the wording, the "Not recorded." rule and the retirement marker in one
place instead of three.

**PURE, AND THAT IS LOAD-BEARING RATHER THAN TIDY.** Nothing here reads a database and nothing here
is asynchronous, because every renderer in this pipeline has to be transliterable into the Kotlin
that produces the on-device report. The answers and the field descriptors arrive already loaded, on
the workshop data, exactly as the transcripts, the questionnaires and the AI layers do.

**WHAT IS DELIBERATELY NOT DRAWN.** No photographs, no files, no formatted text, no map pins: v1
custom fields are scalars and lists of scalars (see ``custom_sections.V1_FIELD_TYPES`` for the five
media walkers that decide it), so there is no image path in this module at all and its absence is
the design rather than an omission.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field as dataclass_field
from typing import Any

from app.services.report_model import (
    Align,
    Block,
    DocumentBuilder,
    KeyValueBlock,
    PageBreakBlock,
    ParagraphBlock,
    ParaStyle,
    ReportMeta,
    clean_text,
    runs_of,
)

#: What a required custom field with no answer prints. The builder's own editorial rule, restated
#: here so the two cannot drift: an empty OPTIONAL field prints nothing, and an empty REQUIRED one
#: prints this — a gap in the record has to be visible AS a gap, or a reader assumes the question
#: was never asked.
NOT_RECORDED = "Not recorded."

#: The marker beside a question that was reworded after it had been answered. The answer stays
#: printed, under the wording it was given, and this is what says so.
#:
#: IT IS THE POINT OF THE WHOLE SUPERSEDE RULE REACHING THE PAGE. "How many looms?" answered "12"
#: and later reworded to "How many weavers?" must never print the 12 under the new wording; the old
#: wording is kept and marked, and both are in the document, which is what makes the record
#: explicable a year later to somebody who was not there.
RETIRED_NOTE = "no longer asked"

#: How many rows one custom section may print. A section is capped at 60 fields at definition time,
#: so this can only bite on a definition written around the API; it is here for the same reason the
#: other annexures carry one — both renderers lay out every row before the designer sees a page.
MAX_ROWS_PER_SECTION = 200


@dataclass(frozen=True, slots=True)
class CustomReportField:
    """One custom field as the report reads it: the question, and how to print its answer.

    A FLAT VALUE OBJECT WITH NO ``CustomFieldSpec`` INSIDE IT, exactly as ``AiLayerItem`` holds no
    Prisma row and ``QuestionnaireAnswer`` holds no ORM object. The reason is the same one that
    makes this module pure: the phone builds this shape from its own cached definition, and a
    renderer that could reach a server-side type would not be transliterable.
    """

    key: str
    label: str
    #: The ``FieldType`` token as a string, never the enum — see the class docstring.
    type: str = "TEXT"
    unit: str = ""
    #: ``(value, label)`` pairs for ENUM and MULTI_ENUM, in the order the designer declared them.
    options: tuple[tuple[str, str], ...] = ()
    required: bool = False
    retired: bool = False

    def option_label(self, value: Any) -> str:
        """The printable label for a stored token, falling back to the token itself.

        Falling back rather than raising, exactly as ``stage_schema.enum_label`` does: a draft
        written before an option was removed still holds that token, and printing it raw is better
        than failing an export a designer is waiting on in the field.
        """
        token = str(value)
        return next((label or token for value_, label in self.options if value_ == token), token)


@dataclass(frozen=True, slots=True)
class CustomSectionItem:
    """One designer-defined section, with the answers recorded against it.

    ``values`` is the ``_custom`` row's ``data`` for the stage this section is asked at — keyed by
    custom field key, flat, exactly as it is stored. Several sections on one stage therefore share
    one ``values`` mapping and each picks its own keys out of it, which is why field keys are unique
    across the whole workshop rather than only within a section.
    """

    key: str
    title: str
    stage_key: str = ""
    description: str = ""
    sort_order: int = 0
    fields: tuple[CustomReportField, ...] = ()
    values: Mapping[str, Any] = dataclass_field(default_factory=dict)

    def answer(self, field: CustomReportField) -> Any:
        return self.values.get(field.key)

    @property
    def printed_fields(self) -> tuple[CustomReportField, ...]:
        """The fields this section actually prints, in order.

        A LIVE field prints whether or not it was answered — an unanswered required one prints
        "Not recorded." and an unanswered optional one prints nothing, which is the builder's rule.
        A RETIRED field prints only when it holds an answer: a question nobody answered and nobody
        asks any more is not evidence of anything, and printing it would put a row of dashes in a
        submitted document for every wording the designer ever corrected.
        """
        return tuple(
            f for f in self.fields
            if not f.retired or _has_answer(self.values.get(f.key))
        )

    @property
    def has_content(self) -> bool:
        """Whether there is anything worth a heading.

        An answered field, or a required field whose absence has to be visible. A section that is
        neither — a block of optional questions nobody has got to yet — appends nothing at all, not
        even the heading, so a report of a workshop that has not reached those questions is exactly
        the report it would have been.
        """
        return any(
            _has_answer(self.values.get(f.key)) or (f.required and not f.retired)
            for f in self.fields
        )

    @property
    def answered_count(self) -> int:
        return sum(1 for f in self.fields if _has_answer(self.values.get(f.key)))


def _has_answer(value: Any) -> bool:
    """Whether a stored value is an answer. The renderer's own half of ``_is_filled``.

    Deliberately NOT an import of ``stage_schema._is_filled``: that function reaches into the
    rich-text model for its dict branch, and this module must stay free of everything a phone port
    cannot carry. v1 custom values are scalars and lists of scalars, so the two agree on every value
    that can reach here — and a v1.1 that admits a dict-shaped type has to come back and say which
    definition it means.
    """
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, dict)):
        return bool(value)
    return True


# --------------------------------------------------------------------------------------
# Carrying the sections to the renderer
# --------------------------------------------------------------------------------------

# The attribute the items travel on, set with ``setattr`` rather than declared on ``WorkshopData``.
# The reasoning is ``report_annexures``' and ``report_questionnaires``', repeated rather than
# referenced because it decides what happens when it fails: ``WorkshopData`` is a plain dataclass in
# a module owned by the report builder, and a feature that can be wired without editing a shared
# dataclass should be.
_ATTR = "custom_sections"


def attach_custom_sections(
    data: Any, items: Sequence[CustomSectionItem] | tuple[CustomSectionItem, ...]
) -> Any:
    """Put the loaded custom sections on the workshop data the builder will walk. Returns ``data``."""
    try:
        setattr(data, _ATTR, tuple(items))
    except AttributeError:
        # A ``slots`` dataclass would refuse the attribute. Losing the sections is the right
        # failure: a report missing a designer's own block is still a report, and raising here would
        # take away their ability to generate anything at all.
        return data
    return data


def custom_sections_of(data: Any) -> tuple[CustomSectionItem, ...]:
    """The custom sections attached to ``data``, or none. Safe on any workshop data object."""
    return tuple(getattr(data, _ATTR, ()) or ())


def custom_scoring(data: Any, stage_key: str) -> tuple[tuple[CustomReportField, ...], dict[str, Any]]:
    """The custom fields and answers of one stage, in the shape ``stage_completeness`` scores.

    THE REPORT MUST NOT COUNT FOR ITSELF, which is the whole reason this returns arguments for that
    function instead of a number. The completeness annexure and the per-stage export warnings both
    re-score every stage at render time, and if they scored a workshop's custom fields by a rule of
    their own the document would contain two arithmetics — which is the defect this repository has
    already shipped once, as "13. Prototype Development | 144/144 | 100% | Complete" printed eighteen
    pages after the same document said "Not recorded." thirty-six times for the field it had counted.

    Several sections can be asked at one stage, so their fields are concatenated in the order they
    print and their values come from the one container they share.
    """
    fields: list[CustomReportField] = []
    values: dict[str, Any] = {}
    for item in sorted(
        (i for i in custom_sections_of(data) if i.stage_key == stage_key),
        key=lambda i: (i.sort_order, i.key),
    ):
        fields.extend(item.fields)
        values.update(item.values)
    return tuple(fields), values


def custom_section_of(data: Any, key: str) -> CustomSectionItem | None:
    """The one section a ``CUSTOM_SECTION`` template section names, or None when it is not attached.

    None is an ordinary outcome and not an error: ``apply_report_settings`` builds the template from
    the definition it was handed, and a definition that changed between the two loads — or a section
    retired while a report was being generated — leaves a template section naming nothing. The
    builder's branch appends nothing in that case, which is the same silence a stage section with no
    data produces.
    """
    return next((item for item in custom_sections_of(data) if item.key == key), None)


# --------------------------------------------------------------------------------------
# The blocks
# --------------------------------------------------------------------------------------


def display_value(field: CustomReportField, value: Any) -> str:
    """One custom answer as the report should print it.

    **THE OPTION TYPES ARE THE ONLY THING THIS FUNCTION DECIDES, AND THAT IS THE WHOLE POINT.**
    Everything else is handed to ``report_builder.format_value`` — the same function that prints
    every registry field — so a custom MONEY prints "₹ 1,25,000.00" with the Indian grouping a core
    MONEY prints, a custom DATE prints in the same format, and a custom INT gets the same unit
    suffix. A second formatter here would produce a document whose own two halves disagree about how
    a rupee is written.

    ENUM and MULTI_ENUM cannot go that way, and not by oversight: ``format_value`` resolves an enum
    label through the shared ``ENUMS`` table, and a designer's list is deliberately not in it (see
    ``custom_sections.CustomOption``). Unresolved, the report would print the raw token —
    "TIE_AND_DYE" in a document submitted to a ministry, which is the failure the shared table
    exists to prevent, reached from the other side.

    THE IMPORT IS INSIDE THE FUNCTION because ``report_builder`` imports THIS module at module
    scope, to reach the branch that calls it. By the time any of this runs the builder is fully
    imported, and doing it this way keeps the dependency pointing in the direction the rest of the
    report pipeline points — the pure modules are imported BY the builder, never the reverse.
    """
    if value is None or value == "" or value == []:
        return ""
    if field.type == "ENUM":
        return field.option_label(value)
    if field.type == "MULTI_ENUM":
        if isinstance(value, (list, tuple)):
            return ", ".join(field.option_label(v) for v in value)
        return field.option_label(value)

    from app.services.report_builder import format_value
    from app.services.stage_schema import FieldSpec, FieldType

    try:
        field_type = FieldType(field.type)
    except ValueError:
        # A type this build has never heard of — a definition written by a newer server, read by an
        # older one. Printed as plain text rather than dropped: the answer is a designer's recorded
        # fieldwork and a report that omits it silently is worse than one that prints it plainly.
        return clean_text(value)
    return format_value(
        FieldSpec(key=field.key, label=field.label, type=field_type, unit=field.unit), value
    )


def _label_runs(field: CustomReportField) -> str:
    """The label as the key-value grid shows it, with the retirement marker when there is one."""
    if field.retired:
        return f"{field.label} ({RETIRED_NOTE})"
    return field.label


def custom_section_blocks(item: CustomSectionItem) -> list[Block]:
    """One section's answers as report blocks, below its heading.

    LONG TEXT IS PROSE AND EVERYTHING ELSE IS A GRID, which is the builder's own editorial rule
    stated in its module docstring: "Long text becomes prose paragraphs under their own sub-heading;
    short values become a key-value grid. Mixing the two in one block is what made the first drafts
    unreadable." A designer's own long answer is prose for the same reason a registry one is.
    """
    pairs: list[tuple[str, Any]] = []
    prose: list[tuple[str, str]] = []
    printed = 0
    truncated = False

    for field in item.printed_fields:
        if printed >= MAX_ROWS_PER_SECTION:
            truncated = True
            break
        printed += 1
        raw = item.answer(field)
        text = display_value(field, raw)
        if field.type == "LONG_TEXT":
            if text.strip():
                prose.append((_label_runs(field), text))
            elif field.required and not field.retired:
                pairs.append((_label_runs(field), NOT_RECORDED))
            continue
        if text.strip():
            pairs.append((_label_runs(field), text))
        elif field.required and not field.retired:
            pairs.append((_label_runs(field), NOT_RECORDED))

    blocks: list[Block] = []
    if pairs:
        blocks.append(KeyValueBlock(
            pairs=tuple((clean_text(label), runs_of(value)) for label, value in pairs)
        ))
    for label, text in prose:
        blocks.append(ParagraphBlock(runs=runs_of(label, bold=True), style=ParaStyle.BODY,
                                     align=Align.LEFT))
        for chunk in (c for c in clean_text(text).split("\n\n") if c.strip()):
            blocks.append(ParagraphBlock(runs=runs_of(chunk.strip()), style=ParaStyle.BODY))
    if truncated:
        blocks.append(ParagraphBlock(
            runs=runs_of(
                f"[Answers truncated after {MAX_ROWS_PER_SECTION} questions. The full set is held "
                f"against the workshop in the repository.]"
            ),
            style=ParaStyle.NOTE,
        ))
    return blocks


def append_custom_section(
    doc: DocumentBuilder,
    item: CustomSectionItem | None,
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = False,
) -> int:
    """Append one custom section to ``doc``. Returns how many answers it printed.

    THE ONE CALL SITE is ``report_builder.ReportBuilder.build``, in the ``if/elif`` chain over
    ``section.special``, beside the three annexure branches. Keep it the only one, for the reason
    those branches state: two callers would be two chances to pass a different heading or a
    different ``numbered``, and a report whose sections are numbered on the phone and unnumbered at
    the office is exactly the divergence the report port exists to end.

    With nothing attached, or a section with nothing worth a heading, this appends nothing at all —
    not even the page break — so a workshop that has not reached those questions produces exactly
    the report it would have produced without them.
    """
    if item is None or not item.has_content:
        return 0
    if page_break_before:
        doc.add(PageBreakBlock())
    doc.heading(heading or item.title, 1, numbered=numbered)
    if item.description.strip():
        doc.para(item.description, style=ParaStyle.LEAD)
    for block in custom_section_blocks(item):
        doc.add(block)
    return item.answered_count


def custom_section_blocks_standalone(
    item: CustomSectionItem, *, heading: str = "", numbered: bool = True
) -> tuple[Block, ...]:
    """The section as a plain block tuple, built in isolation — for tests and for a preview.

    The heading numbers restart at 1 because nothing else has been counted, which is why the real
    report goes through :func:`append_custom_section` instead.
    """
    scratch = DocumentBuilder(meta=ReportMeta(title=""))
    append_custom_section(scratch, item, heading=heading, numbered=numbered,
                          page_break_before=False)
    return scratch.build().blocks


__all__ = [
    "MAX_ROWS_PER_SECTION",
    "NOT_RECORDED",
    "RETIRED_NOTE",
    "CustomReportField",
    "CustomSectionItem",
    "append_custom_section",
    "attach_custom_sections",
    "custom_scoring",
    "custom_section_blocks",
    "custom_section_blocks_standalone",
    "custom_section_of",
    "custom_sections_of",
    "display_value",
]
