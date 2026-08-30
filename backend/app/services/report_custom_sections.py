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

#: The capture-tier ladder as plain tokens, so this module can answer "does the template admit this
#: question" without importing :class:`app.services.stage_schema.Tier`.
#:
#: **A SECOND COPY OF A RULE IS HOW MOST OF THIS REPOSITORY'S DEFECTS AROSE, SO SAY WHY THIS ONE
#: EXISTS.** This module is pure and has to stay transliterable into the Kotlin that builds the
#: on-device report (see the module docstring) — ``Tier`` lives beside the whole 22-stage registry
#: and dragging it in at module scope would put the registry's import graph inside the phone's
#: renderer. The copy is three tokens that have not moved since the source matrix was written, and
#: ``test_the_local_tier_ladder_is_the_registrys_own`` compares this dict against ``Tier`` member by
#: member, so the two cannot silently diverge. If a fourth tier is ever added, that test fails
#: before anything reaches a document.
_TIER_RANK: dict[str, int] = {"BASIC": 0, "STANDARD": 1, "ADVANCED": 2}

#: The rank that admits every tier. The default everywhere below, so a caller that has no template
#: in hand — a test, the web preview, ``custom_section_blocks_standalone`` — keeps printing every
#: question exactly as it did before tiers were consulted at all.
ALL_TIERS = 2


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
    #: The capture tier the designer chose for this question, as a ``Tier`` token string.
    #:
    #: **WHY THIS IS HERE AT ALL.** ``CustomFieldSpec.tier`` is a real choice in the section editor
    #: ("Which capture tier this question belongs to"), it is validated, it is stored, and it is
    #: serialised to both clients — and until this attribute existed it was read by nothing at
    #: render time. Every registry field passes ``ReportBuilder._visible``
    #: (``spec.tier.rank <= template.max_tier.rank``); a designer's own question passed no such
    #: gate, so COMPACT_SUMMARY — whose stated description is "Basic-tier fields only, one
    #: photograph per prototype" and whose ``max_tier`` is the only non-ADVANCED one in
    #: ``TEMPLATES`` — correctly suppressed every Standard and Advanced REGISTRY field and then
    #: printed the designer's Standard-tier answers in full. One document, two rules, one declared
    #: attribute.
    #:
    #: **THE DEFAULT IS BASIC AND NOT STANDARD, WHICH IS THE OPPOSITE OF ``CustomFieldSpec``'s.**
    #: That is deliberate and it is the safe direction. BASIC is the rank every template admits, so
    #: a ``CustomReportField`` built by a caller that does not yet supply a tier prints exactly
    #: where it printed before — whereas defaulting to STANDARD would make one missing keyword
    #: argument silently delete a designer's genuinely Basic-tier questions from a submitted
    #: report. Losing recorded fieldwork out of a ministry's copy is a far worse failure than
    #: printing an answer a terse template might not have asked for, and the same asymmetry is why
    #: ``display_value`` prints an unknown field type as plain text rather than dropping it.
    tier: str = "BASIC"

    @property
    def tier_rank(self) -> int:
        """This field's rank on the capture ladder; ``BASIC`` for anything unrecognised.

        Unrecognised falls to 0 — printed by every template — for the reason the ``tier`` default
        gives: a definition written by a newer server and read by an older one must not lose the
        designer's answers to a token this build has never heard of.
        """
        return _TIER_RANK.get(str(self.tier).upper(), 0)

    def within(self, max_tier_rank: int) -> bool:
        """Whether a template capped at ``max_tier_rank`` asks this question."""
        return self.tier_rank <= max_tier_rank

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

    def fields_at(self, max_tier_rank: int = ALL_TIERS) -> tuple[CustomReportField, ...]:
        """Every field of this section a template capped at ``max_tier_rank`` asks about.

        THE ONE PLACE THE TIER GATE IS APPLIED. ``printed_fields``, ``has_content`` and
        ``answered_count`` all come through here, so a template cannot print a question it did not
        count or count one it did not print — which is the shape of the defect this repository has
        already shipped once, as a completeness table claiming 100% eighteen pages after the same
        document said "Not recorded." thirty-six times.
        """
        return tuple(f for f in self.fields if f.within(max_tier_rank))

    def printed_fields_at(self, max_tier_rank: int = ALL_TIERS) -> tuple[CustomReportField, ...]:
        """The fields this section actually prints under a tier cap, in order.

        A LIVE field prints whether or not it was answered — an unanswered required one prints
        "Not recorded." and an unanswered optional one prints nothing, which is the builder's rule.
        A RETIRED field prints only when it holds an answer: a question nobody answered and nobody
        asks any more is not evidence of anything, and printing it would put a row of dashes in a
        submitted document for every wording the designer ever corrected.
        """
        return tuple(
            f
            for f in self.fields_at(max_tier_rank)
            if not f.retired or _has_answer(self.values.get(f.key))
        )

    @property
    def printed_fields(self) -> tuple[CustomReportField, ...]:
        """:meth:`printed_fields_at` with every tier admitted. Kept as a property because it is the
        shape three callers outside the renderer already read."""
        return self.printed_fields_at()

    def has_content_at(self, max_tier_rank: int = ALL_TIERS) -> bool:
        """Whether there is anything worth a heading under a tier cap.

        An answered field, or a required field whose absence has to be visible. A section that is
        neither — a block of optional questions nobody has got to yet — appends nothing at all, not
        even the heading, so a report of a workshop that has not reached those questions is exactly
        the report it would have been.

        ASKED AT THE SAME CAP THE RENDERER WILL USE, or a terse template grows an empty heading over
        nothing: a Standard-tier block on a COMPACT_SUMMARY would answer "yes, there is content",
        print its heading, and then find every one of its fields filtered out below.
        """
        return any(
            _has_answer(self.values.get(f.key)) or (f.required and not f.retired)
            for f in self.fields_at(max_tier_rank)
        )

    @property
    def has_content(self) -> bool:
        """:meth:`has_content_at` with every tier admitted.

        THIS IS THE CAP-BLIND READING AND IT ANSWERS ONE CALLER'S QUESTION, NOT THE RENDERER'S.
        ``design_workshops.attach_report_custom_sections`` asks it because it runs before any
        template has been resolved and so has no cap to offer; everything that HAS a template must
        go through :func:`section_prints` with it. Reaching for this property where a cap is in
        hand is how the render and the warning list came to tell two stories about one section —
        see :func:`section_prints` for the incident.
        """
        return self.has_content_at()

    def answered_count_at(self, max_tier_rank: int = ALL_TIERS) -> int:
        return sum(1 for f in self.fields_at(max_tier_rank) if _has_answer(self.values.get(f.key)))

    @property
    def answered_count(self) -> int:
        return self.answered_count_at()


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


def custom_scoring(
    data: Any, stage_key: str
) -> tuple[tuple[CustomReportField, ...], dict[str, Any]]:
    """The custom fields and answers of one stage, in the shape ``stage_completeness`` scores.

    THE REPORT MUST NOT COUNT FOR ITSELF, which is the whole reason this returns arguments for that
    function instead of a number. The completeness annexure and the per-stage export warnings both
    re-score every stage at render time, and if they scored a workshop's custom fields by a rule of
    their own the document would contain two arithmetics — which is the defect this repository has
    already shipped once, as "13. Prototype Development | 144/144 | 100% | Complete" printed eighteen
    pages after the same document said "Not recorded." thirty-six times for the field it had counted.

    Several sections can be asked at one stage, so their fields are concatenated in the order they
    print and their values come from the one container they share.

    **NO TIER FILTER HERE, AND THAT IS NOT AN OMISSION — IT IS THE ONLY READING THAT MATCHES THE
    REGISTRY.** ``stage_completeness`` has no tier test of any kind: it scores every non-deprecated
    field a stage declares, whatever the template's ``max_tier``, because completeness is a fact
    about the FIELDWORK and not about the document somebody chose to print. A registry field
    suppressed from a COMPACT_SUMMARY still counts against that stage's percentage, and a designer's
    Standard-tier question must count the same way or the workshop would score differently depending
    on which template a reader happened to pick. So ``fields`` and not ``fields_at`` — and if you are
    here because you are adding a cap, the number this feeds is compared against the readiness
    screen's, and the two arithmetics diverging is this repository's oldest report defect.
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


def section_prints(item: CustomSectionItem | None, max_tier_rank: int = ALL_TIERS) -> bool:
    """**THE** answer to "will this section appear in the document". One predicate, two readers.

    ``append_custom_section`` asks it before it writes a heading, and
    ``report_builder.build_report`` asks it again — same function, same item, same cap — before it
    tells the designer which of their sections are missing from the file. That is the whole reason
    this exists as a named function rather than as a condition inside the appender.

    **THE DEFECT THAT MADE IT NECESSARY, WHICH IS THE SECOND OF ITS SHAPE ON THIS ONE FEATURE.**
    Two fixes landed on this render in the same afternoon. One gave the appender a tier cap, so a
    designer's Standard-tier question stopped printing under COMPACT_SUMMARY ("Basic-tier fields
    only", the only non-ADVANCED ``max_tier`` in ``TEMPLATES``). The other re-pointed the loader's
    "not in this file" warning at ``has_content``, because it had been firing for exactly the
    sections the renderer DOES print — a designer was told "Dye bath log … is not in this file"
    about a document containing the heading "Dye bath log" and "Dye source — Not recorded."
    underneath it.

    Separately each was right. Together they left a section whose every answered question sits above
    the template's cap: the renderer prints nothing, and the warning — which has no template to ask
    and so reads the cap-blind ``has_content`` — says nothing either. The designer submits a .docx
    with their block silently absent, told nothing, which is the FIRST defect's cost arrived at from
    the other side. A document and its own warning list must not tell a ministry two stories about
    one section, in either direction.

    So: a section that does not print is named exactly once, and the two halves of that guarantee
    are ``has_content`` at :data:`ALL_TIERS` (the loader's half — see
    ``design_workshops.attach_report_custom_sections``, which is called before any template is
    resolved and so can honestly ask nothing else) and :func:`sections_hidden_by_tier` at the
    template's own cap (the builder's half, which has the template in hand). They are disjoint by
    construction because ``fields_at`` is monotone in the cap, and
    ``test_report_custom_section_tier_warning`` pins both the disjointness and the coverage.

    ``None`` is not printing and is not warned about: an item that is not attached at all was never
    the template's to print — see :func:`custom_section_of` for when that happens.
    """
    return item is not None and item.has_content_at(max_tier_rank)


def sections_hidden_by_tier(
    items: Sequence[CustomSectionItem | None], max_tier_rank: int
) -> tuple[CustomSectionItem, ...]:
    """The sections that would have printed, and do not, because of THIS template's tier cap.

    Exactly the residue the loader's warning cannot see: ``section_prints`` is true at
    :data:`ALL_TIERS` (so ``attach_report_custom_sections`` said nothing about it) and false at the
    template's cap (so the renderer wrote nothing). Both halves go through :func:`section_prints`
    rather than through a hand-rolled tier comparison, because a second copy of that decision is
    precisely how the render and the warning came apart in the first place.

    ``None`` entries are tolerated and dropped, so the builder can hand this the result of
    ``custom_section_of`` for every ``CUSTOM_SECTION`` the template carries without filtering first
    — a template section naming a definition that changed between the two loads is an ordinary
    outcome, not something to warn a designer about.
    """
    return tuple(
        item
        for item in items
        if section_prints(item, ALL_TIERS) and not section_prints(item, max_tier_rank)
    )


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


def custom_section_blocks(
    item: CustomSectionItem, *, max_tier_rank: int = ALL_TIERS
) -> list[Block]:
    """One section's answers as report blocks, below its heading.

    LONG TEXT IS PROSE AND EVERYTHING ELSE IS A GRID, which is the builder's own editorial rule
    stated in its module docstring: "Long text becomes prose paragraphs under their own sub-heading;
    short values become a key-value grid. Mixing the two in one block is what made the first drafts
    unreadable." A designer's own long answer is prose for the same reason a registry one is.

    ``max_tier_rank`` is the template's ``max_tier.rank``, passed as a bare int rather than a
    ``Tier`` so this module stays free of the registry's import graph — see ``_TIER_RANK``. It
    defaults to admitting everything, which is what every caller without a template does.
    """
    pairs: list[tuple[str, Any]] = []
    prose: list[tuple[str, str]] = []
    printed = 0
    truncated = False

    for field in item.printed_fields_at(max_tier_rank):
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
        blocks.append(
            KeyValueBlock(
                pairs=tuple((clean_text(label), runs_of(value)) for label, value in pairs)
            )
        )
    for label, text in prose:
        blocks.append(
            ParagraphBlock(runs=runs_of(label, bold=True), style=ParaStyle.BODY, align=Align.LEFT)
        )
        for chunk in (c for c in clean_text(text).split("\n\n") if c.strip()):
            blocks.append(ParagraphBlock(runs=runs_of(chunk.strip()), style=ParaStyle.BODY))
    if truncated:
        blocks.append(
            ParagraphBlock(
                runs=runs_of(
                    f"[Answers truncated after {MAX_ROWS_PER_SECTION} questions. The full set is held "
                    f"against the workshop in the repository.]"
                ),
                style=ParaStyle.NOTE,
            )
        )
    return blocks


def append_custom_section(
    doc: DocumentBuilder,
    item: CustomSectionItem | None,
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = False,
    max_tier_rank: int = ALL_TIERS,
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

    ``max_tier_rank`` IS PASSED HERE AND NOT DECIDED HERE, exactly as ``numbered`` is. The template
    owns the cap; this function owns the wording. Both the emptiness test and the block walk read
    the SAME cap, so a section whose every question sits above the template's tier appends nothing
    rather than an empty heading.

    THE EMPTINESS TEST IS :func:`section_prints` AND NOT AN INLINE ``has_content_at``, because
    ``build_report`` has to ask the identical question afterwards to warn about what it skipped.
    Two spellings of "did this print" is how the renderer and the warning list came to tell a
    designer two different stories about one section; see that function for the incident.
    """
    if item is None or not section_prints(item, max_tier_rank):
        return 0
    if page_break_before:
        doc.add(PageBreakBlock())
    doc.heading(heading or item.title, 1, numbered=numbered)
    if item.description.strip():
        doc.para(item.description, style=ParaStyle.LEAD)
    for block in custom_section_blocks(item, max_tier_rank=max_tier_rank):
        doc.add(block)
    return item.answered_count_at(max_tier_rank)


def custom_section_blocks_standalone(
    item: CustomSectionItem, *, heading: str = "", numbered: bool = True
) -> tuple[Block, ...]:
    """The section as a plain block tuple, built in isolation — for tests and for a preview.

    The heading numbers restart at 1 because nothing else has been counted, which is why the real
    report goes through :func:`append_custom_section` instead.
    """
    scratch = DocumentBuilder(meta=ReportMeta(title=""))
    append_custom_section(
        scratch, item, heading=heading, numbered=numbered, page_break_before=False
    )
    return scratch.build().blocks


__all__ = [
    "ALL_TIERS",
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
    "section_prints",
    "sections_hidden_by_tier",
]
