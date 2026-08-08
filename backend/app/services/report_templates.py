"""The report templates — which stages a report prints, in what order, and how.

The source document's stage 20 asks the designer to "select DCH/DIC/agency report template" and
then to "auto-populate tables, photos, captions, participant lists, cost sheets". A template
here is exactly that selection: a declaration of which of the 22 stages appear, under what
headings, at which capture tier, and in which presentation.

What a template deliberately is NOT is a layout. It never mentions a font, a column width or a
page break. Those belong to :mod:`app.services.report_model`'s theme and to the two renderers,
and keeping them out is what lets the same six templates produce both a .docx and a .pdf, on a
server and on a phone, without six more copies of the layout rules.

The presentation choice per section is the interesting part. The same stage data can be printed
three ways, and which one is right depends on the audience rather than on the data:

    KEY_VALUE   a label/value grid            — an administrative annexure
    NARRATIVE   prose paragraphs under headings — the body of a submitted report
    TABLE       one row per record             — participant lists, cost sheets, sketch indexes
    CARDS       a heading and photo per record — a prototype catalogue

``PHOTO_CATALOGUE`` and ``DETAILED_TECHNICAL`` are the same twenty-two stages; they differ only
in these choices and in ``max_tier``. That is the whole point of the indirection.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Any

from app.services.report_model import PageSize, ReportTheme
from app.services.stage_schema import Tier


class Presentation(str, Enum):
    KEY_VALUE = "KEY_VALUE"
    NARRATIVE = "NARRATIVE"
    TABLE = "TABLE"
    CARDS = "CARDS"
    GALLERY = "GALLERY"
    AUTO = "AUTO"          # let the builder decide from the fields' report_role


class SpecialSection(str, Enum):
    """Sections that are not one of the 22 stages."""

    COVER = "COVER"
    TOC = "TOC"
    ACKNOWLEDGEMENT = "ACKNOWLEDGEMENT"
    SUMMARY_METRICS = "SUMMARY_METRICS"
    SIGNATURES = "SIGNATURES"
    ANNEXURE_MEDIA = "ANNEXURE_MEDIA"
    # Every recording the workshop collected, transcribed, printed in full at the back. The blocks
    # are built by app/services/report_annexures.py rather than by the builder, which reaches them
    # from one branch of `ReportBuilder.build`; rendered only when the designer asked for it through
    # the stage-20 `includeTranscripts` toggle — a template carrying this section prints nothing at
    # all when no transcripts were attached to the workshop data.
    ANNEXURE_TRANSCRIPTS = "ANNEXURE_TRANSCRIPTS"
    COMPLETENESS = "COMPLETENESS"
    # A map of India with the workshop's venue and its artisans' home districts pinned on it.
    # Prints nothing at all until stage 1 names a state or a district, because a map of the whole
    # country with no idea which part of it this workshop happened in is a decoration.
    MAP = "MAP"
    # One or more infographics, named by :attr:`TemplateSection.figures`. Prints nothing when the
    # record cannot fill any of them — see ``report_builder.ReportBuilder.FIGURES``.
    CHART = "CHART"


@dataclass(frozen=True, slots=True)
class TemplateSection:
    """One section of a report.

    Exactly one of ``stage_key`` and ``special`` is set. ``heading`` overrides the stage's own
    title when a ministry format insists on its own section names — which they do, and which is
    most of the difference between the DCH and DIC templates.
    """

    stage_key: str = ""
    special: SpecialSection | None = None
    heading: str = ""
    presentation: Presentation = Presentation.AUTO
    include_photos: bool = True
    photo_columns: int = 2
    max_photos: int = 0          # 0 = no cap; a photo catalogue wants every one
    page_break_before: bool = False
    omit_if_empty: bool = True
    intro: str = ""              # a fixed sentence printed before the data
    entities: tuple[str, ...] = ()   # restrict to these entity keys; empty means all
    # MAY THIS STAGE SECTION PRINT ITS OWN FIGURES. On by default, because a chart that has to be
    # opted into per template is a chart six templates forget. Turned off where an infographic
    # would say something the audience must not be told — see the price list in PHOTO_CATALOGUE.
    include_figures: bool = True
    # For a CHART section: which figures, by the ids ``report_builder.ReportBuilder.FIGURES``
    # declares. Empty means every figure the record can fill, which is what a general "in figures"
    # page wants. The ids are plain strings rather than an enum because this module must not import
    # the builder — the builder imports THIS one — and a test pins every name against the catalogue
    # so a typo fails the suite instead of silently printing no figure.
    figures: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if bool(self.stage_key) == bool(self.special):
            raise ValueError("a TemplateSection needs exactly one of stage_key or special")


@dataclass(frozen=True, slots=True)
class ReportTemplate:
    id: str
    name: str
    description: str
    sections: tuple[TemplateSection, ...]
    max_tier: Tier = Tier.ADVANCED
    page_size: PageSize = PageSize.A4
    theme: ReportTheme = field(default_factory=ReportTheme)
    number_headings: bool = True
    organisation: str = ""
    show_empty_note: bool = True   # print "Not recorded." rather than a silent gap

    def section_for(self, stage_key: str) -> TemplateSection | None:
        return next((s for s in self.sections if s.stage_key == stage_key), None)


# --------------------------------------------------------------------------------------
# The stage running order
# --------------------------------------------------------------------------------------

# The source document names this "the core operational sequence": Administrative & Background ->
# Craft/Cluster Baseline -> Survey -> Analysis & Design Brief -> Sketches -> Sketch Review ->
# Prototype Development -> Iteration -> Selection -> Final Documentation -> Report -> Follow-up.
#
# It is NOT the same as the capture order. The document's own reviewer notes that stages 5 and 6
# "may come later when they actually go to the field after market and consumer survey" — so the
# app must let those be filled whenever, while the report still prints them where a reader
# expects them. Capture order is the designer's; this order is the reader's.
NARRATIVE_ORDER: tuple[str, ...] = (
    "WORKSHOP_SETUP",
    "INTRODUCTORY_ADMIN_DOCUMENTATION",
    "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
    "CLUSTER_CRAFT_BACKGROUND",
    "TRADITIONAL_PROCESS_BASELINE",
    "EXISTING_PRODUCTS_BASELINE",
    "SURVEY_PLANNING",
    "MARKET_SURVEY_CAPTURE",
    "MARKET_ANALYSIS_DIRECTION",
    "DESIGN_BRIEF",
    "SKETCH_DEVELOPMENT",
    "SKETCH_REVIEW",
    "PROTOTYPE_DEVELOPMENT",
    "PROTOTYPE_ITERATION",
    "PROTOTYPE_VALIDATION",
    "FINAL_PROTOTYPE_DOCUMENTATION",
    "COSTING_MARKET_LINKAGE",
    "WORKSHOP_OUTCOMES",
    "INSPECTION_CLOSING",
    "POST_WORKSHOP_FOLLOWUP",
)

# Stage 20 configures the report and stage 21 records the archive; neither is a section OF the
# report. Printing them would be the report describing its own generation.
NON_PRINTING_STAGES: frozenset[str] = frozenset({"REPORT_GENERATION", "DATA_QUALITY_ARCHIVE"})

# Which stages are naturally a table of records rather than prose.
_TABULAR = {
    "WORKSHOP_PLAN_PARTICIPANTS_OPENING": Presentation.TABLE,
    "SURVEY_PLANNING": Presentation.TABLE,
    "MARKET_SURVEY_CAPTURE": Presentation.TABLE,
    "SKETCH_DEVELOPMENT": Presentation.CARDS,
    "SKETCH_REVIEW": Presentation.TABLE,
    "PROTOTYPE_DEVELOPMENT": Presentation.CARDS,
    "PROTOTYPE_ITERATION": Presentation.TABLE,
    "PROTOTYPE_VALIDATION": Presentation.TABLE,
    "FINAL_PROTOTYPE_DOCUMENTATION": Presentation.CARDS,
    "COSTING_MARKET_LINKAGE": Presentation.TABLE,
    "POST_WORKSHOP_FOLLOWUP": Presentation.TABLE,
}

_HEAVY_STAGES = frozenset({
    "PROTOTYPE_DEVELOPMENT", "FINAL_PROTOTYPE_DOCUMENTATION", "SKETCH_DEVELOPMENT",
    "MARKET_SURVEY_CAPTURE",
})


#: The map, placed immediately after the participant roster in every template that carries it.
#: THAT POSITION IS THE FIGURE'S ARGUMENT. A map of home districts printed anywhere else is a
#: decorative map of India; printed under the table of thirty names it is the answer to the
#: question that table raises, which is where those thirty people came from.
MAP_SECTION = TemplateSection(
    special=SpecialSection.MAP,
    heading="Where the workshop was held and where its artisans live",
)

#: The two outcome figures, at the FRONT of the report rather than beside the outcomes stage
#: twenty pages in. An officer who reads three pages of a sixty-page submission must meet the
#: yield of the workshop in one of them. The cost and follow-up figures deliberately stay with
#: their own stages: a cost breakdown on page two, before the cost sheet that produced it, invites
#: a reader to quote a total whose basis they have not seen.
OUTCOME_FIGURES = TemplateSection(
    special=SpecialSection.CHART,
    heading="Outcomes in figures",
    figures=("OUTPUT_COUNTS", "PROTOTYPE_STATUS"),
)


def _standard_sections(*, photos: bool = True, photo_columns: int = 2,
                       figures: bool = False) -> tuple[TemplateSection, ...]:
    """The default running order, used by most templates with small overrides."""
    sections: list[TemplateSection] = [
        TemplateSection(special=SpecialSection.COVER),
        TemplateSection(special=SpecialSection.TOC),
        TemplateSection(special=SpecialSection.SUMMARY_METRICS,
                        heading="Workshop at a glance"),
    ]
    if figures:
        sections.append(OUTCOME_FIGURES)
    for stage_key in NARRATIVE_ORDER:
        sections.append(TemplateSection(
            stage_key=stage_key,
            presentation=_TABULAR.get(stage_key, Presentation.AUTO),
            include_photos=photos,
            photo_columns=photo_columns,
            # A stage that fills several pages reads better from the top of one.
            page_break_before=stage_key in _HEAVY_STAGES,
        ))
        if figures and stage_key == "WORKSHOP_PLAN_PARTICIPANTS_OPENING":
            sections.append(MAP_SECTION)
    sections.append(TemplateSection(special=SpecialSection.SIGNATURES,
                                    page_break_before=True))
    return tuple(sections)


# The transcript annexure, carried by EVERY template. It is a toggle, not a template choice: a
# designer who recorded six artisans and wants their words in the report should get them whichever
# format the office asked for, and having to switch template to reach an appendix would mean
# switching the whole report's section list and heading names as well. The section prints nothing
# unless transcripts were attached (see app/services/report_annexures.py), so a template carrying it
# is byte-for-byte the template it was before whenever the toggle is off — which is the default.
TRANSCRIPT_ANNEXURE = TemplateSection(
    special=SpecialSection.ANNEXURE_TRANSCRIPTS,
    heading="Annexure — Recordings and transcripts",
    page_break_before=True,
)


DCH_THEME = ReportTheme(
    accent="1F3864", accent_soft="2F5496", table_header_fill="1F3864",
    zebra_fill="F2F5FA", rule="B8C4D9",
)
DIC_THEME = ReportTheme(
    accent="1B4332", accent_soft="2D6A4F", table_header_fill="1B4332",
    zebra_fill="EFF6F1", rule="BBD3C4",
)
AGENCY_THEME = ReportTheme(
    accent="6B2737", accent_soft="8C3A4D", table_header_fill="6B2737",
    zebra_fill="FBF1F3", rule="E0C3CB",
)
CATALOGUE_THEME = ReportTheme(
    accent="1B1B1B", accent_soft="4A4A4A", table_header_fill="2B2B2B",
    zebra_fill="F5F5F4", rule="D6D3D1", base_size_pt=10.0,
)


TEMPLATES: tuple[ReportTemplate, ...] = (
    ReportTemplate(
        id="DCH_STANDARD",
        name="DCH standard workshop report",
        description=(
            "The full narrative report for submission to the Development Commissioner "
            "(Handicrafts): cover, contents, every stage in the reader's order, photographs, "
            "cost sheets and sign-off."
        ),
        sections=(*_standard_sections(figures=True), TRANSCRIPT_ANNEXURE),
        organisation="Office of the Development Commissioner (Handicrafts)",
        theme=DCH_THEME,
    ),
    ReportTemplate(
        id="DIC_STANDARD",
        name="DIC standard workshop report",
        description=(
            "The District Industries Centre format. The same content as the DCH report with "
            "the section names a DIC submission expects and the administrative annexure "
            "brought forward."
        ),
        sections=(*_standard_sections(), TRANSCRIPT_ANNEXURE),
        organisation="District Industries Centre",
        theme=DIC_THEME,
    ),
    ReportTemplate(
        id="IMPLEMENTING_AGENCY",
        name="Implementing agency format",
        description=(
            "For the implementing agency's own file: outcomes, costs and prototypes first, "
            "with the cluster and survey background reduced to an annexure."
        ),
        sections=(
            TemplateSection(special=SpecialSection.COVER),
            TemplateSection(special=SpecialSection.TOC),
            TemplateSection(special=SpecialSection.SUMMARY_METRICS,
                            heading="Outcomes at a glance"),
            TemplateSection(stage_key="WORKSHOP_OUTCOMES", presentation=Presentation.NARRATIVE),
            TemplateSection(stage_key="FINAL_PROTOTYPE_DOCUMENTATION",
                            presentation=Presentation.CARDS, page_break_before=True),
            TemplateSection(stage_key="COSTING_MARKET_LINKAGE", presentation=Presentation.TABLE),
            TemplateSection(stage_key="PROTOTYPE_VALIDATION", presentation=Presentation.TABLE),
            TemplateSection(stage_key="WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                            presentation=Presentation.TABLE, page_break_before=True,
                            heading="Annexure A — Participants"),
            TemplateSection(stage_key="CLUSTER_CRAFT_BACKGROUND",
                            presentation=Presentation.KEY_VALUE,
                            heading="Annexure B — Cluster and craft background"),
            TemplateSection(stage_key="MARKET_SURVEY_CAPTURE",
                            presentation=Presentation.TABLE,
                            heading="Annexure C — Market survey"),
            TemplateSection(stage_key="POST_WORKSHOP_FOLLOWUP",
                            presentation=Presentation.TABLE),
            TemplateSection(special=SpecialSection.SIGNATURES, page_break_before=True),
            TRANSCRIPT_ANNEXURE,
        ),
        theme=AGENCY_THEME,
    ),
    ReportTemplate(
        id="COMPACT_SUMMARY",
        name="Compact summary",
        description=(
            "A few pages for a review meeting: what was done, what came out of it and what it "
            "cost. Basic-tier fields only, one photograph per prototype."
        ),
        max_tier=Tier.BASIC,
        sections=(
            TemplateSection(special=SpecialSection.COVER),
            TemplateSection(special=SpecialSection.SUMMARY_METRICS,
                            heading="Workshop at a glance"),
            TemplateSection(stage_key="WORKSHOP_SETUP", presentation=Presentation.KEY_VALUE),
            TemplateSection(stage_key="CLUSTER_CRAFT_BACKGROUND",
                            presentation=Presentation.NARRATIVE, include_photos=False),
            TemplateSection(stage_key="MARKET_ANALYSIS_DIRECTION",
                            presentation=Presentation.NARRATIVE, include_photos=False),
            TemplateSection(stage_key="DESIGN_BRIEF", presentation=Presentation.NARRATIVE,
                            include_photos=False),
            TemplateSection(stage_key="FINAL_PROTOTYPE_DOCUMENTATION",
                            presentation=Presentation.TABLE, photo_columns=3, max_photos=6),
            TemplateSection(stage_key="COSTING_MARKET_LINKAGE", presentation=Presentation.TABLE,
                            include_photos=False),
            TemplateSection(stage_key="WORKSHOP_OUTCOMES", presentation=Presentation.NARRATIVE,
                            include_photos=False),
            TemplateSection(special=SpecialSection.SIGNATURES),
            TRANSCRIPT_ANNEXURE,
        ),
        theme=DCH_THEME,
    ),
    ReportTemplate(
        id="DETAILED_TECHNICAL",
        name="Detailed technical report",
        description=(
            "Everything captured, including the Advanced tier: process sequences, material "
            "specifications, iteration histories, quality assessments and the full media "
            "annexure. The archival copy."
        ),
        sections=(
            *_standard_sections(photo_columns=2, figures=True),
            TemplateSection(special=SpecialSection.ANNEXURE_MEDIA, page_break_before=True,
                            heading="Annexure — Photographic record"),
            TRANSCRIPT_ANNEXURE,
            TemplateSection(special=SpecialSection.COMPLETENESS,
                            heading="Annexure — Data completeness"),
        ),
        theme=DCH_THEME,
    ),
    ReportTemplate(
        id="PHOTO_CATALOGUE",
        name="Photo catalogue",
        description=(
            "A buyer-facing catalogue: the final products, large, with their dimensions, "
            "materials and prices. Almost no prose."
        ),
        sections=(
            TemplateSection(special=SpecialSection.COVER),
            TemplateSection(stage_key="FINAL_PROTOTYPE_DOCUMENTATION",
                            presentation=Presentation.CARDS, photo_columns=1,
                            heading="Products"),
            TemplateSection(stage_key="COSTING_MARKET_LINKAGE",
                            presentation=Presentation.TABLE, include_photos=False,
                            heading="Price list", page_break_before=True,
                            # NO COST BREAKDOWN IN A DOCUMENT THAT GOES TO A BUYER. This template
                            # is the buyer-facing catalogue, and the cost-by-head figure prints
                            # the maker's material and labour cost beside the price the buyer is
                            # being quoted. Every other template wants that chart; this one hands
                            # the cluster's margin to the person negotiating against it.
                            include_figures=False),
            TemplateSection(stage_key="WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                            presentation=Presentation.TABLE, include_photos=False,
                            heading="The makers"),
            TRANSCRIPT_ANNEXURE,
        ),
        number_headings=False,
        theme=CATALOGUE_THEME,
    ),
)


def template(template_id: str) -> ReportTemplate:
    """Look up a template, falling back to the DCH standard.

    Falling back rather than raising is deliberate: a saved workshop can name a template that a
    later release removed, and losing the ability to print a two-week workshop because its
    template was retired is not an acceptable outcome. The caller records the substitution as an
    export warning.
    """
    found = next((t for t in TEMPLATES if t.id == template_id), None)
    return found or TEMPLATES[0]


# The stage-20 answers that shape the template rather than the cover. Named here, once, because
# three call sites used to each decide for themselves which keys mattered and they disagreed.
_SECTION_TOGGLES: dict[str, SpecialSection] = {
    "includeTableOfContents": SpecialSection.TOC,
    "includeMediaAnnexure": SpecialSection.ANNEXURE_MEDIA,
    "includeCompletenessAnnexure": SpecialSection.COMPLETENESS,
}


def _asked(settings: Mapping[str, Any] | None, key: str) -> bool | None:
    """A tri-state read of a stage-20 boolean: True, False, or "the designer never said".

    The distinction is the whole reason this is not ``bool(settings.get(key))``. A template that
    prints a table of contents must keep printing one for every workshop saved before the toggle
    existed, so ABSENT has to mean "leave the template alone" and only an explicit ``false`` may
    take the section away.
    """
    if not settings:
        return None
    raw = settings.get(key)
    if raw is None or raw == "":
        return None
    return bool(raw)


def apply_report_settings(
    template_: ReportTemplate,
    settings: Mapping[str, Any] | None,
    *,
    include_photographs: bool | None = None,
) -> ReportTemplate:
    """Return the template the designer's stage-20 answers actually ask for.

    THIS FUNCTION IS WHY THOSE ANSWERS ARE NOT DECORATION. Seven of them — the table of contents,
    the photographic and completeness annexures, whether photographs print at all, how many to a
    row, whether headings are numbered, and which stages to leave out — were stored faithfully by
    the form, written to the database, and then read by nothing whatsoever. A designer who turned
    "Number the headings" on saw a report with unnumbered headings and no way to tell that the
    switch was inert, which is worse than not offering the switch: an absent feature is obvious
    and a silent one is a bug the designer blames themselves for.

    The precedence is the one the rest of the pipeline already uses (``wants_transcripts``,
    ``resolve_accent``): an explicit request argument wins, the saved stage-20 answer applies
    otherwise, and where neither spoke the template keeps its own value untouched. That last
    clause is load-bearing — it is what stops this from restyling every report a deployment has
    ever generated the moment it ships.

    ``ReportTemplate`` is frozen, so this returns a NEW template and never mutates the module's
    shared ``TEMPLATES`` tuple. Mutating it would leak one designer's choices into every other
    designer's report for the lifetime of the process, which is exactly the kind of bug that only
    reproduces under load.
    """
    if not settings and include_photographs is None:
        return template_

    sections = list(template_.sections)

    # ── which sections survive ────────────────────────────────────────────────────────────────
    for key, special in _SECTION_TOGGLES.items():
        if _asked(settings, key) is False:
            sections = [s for s in sections if s.special is not special]

    # ``excludedStages`` is a TAGS field, so it arrives as a list of stage keys. Unknown keys are
    # ignored rather than rejected: a phone one release ahead may name a stage this build has not
    # heard of, and refusing the whole report over it would lose the fieldwork.
    excluded = settings.get("excludedStages") if settings else None
    if isinstance(excluded, (list, tuple)):
        drop = {str(k).strip() for k in excluded if str(k).strip()}
        if drop:
            sections = [s for s in sections if not (s.stage_key and s.stage_key in drop)]

    # ── how the surviving sections print ──────────────────────────────────────────────────────
    photos = include_photographs
    if photos is None:
        photos = _asked(settings, "includePhotographs")

    columns = settings.get("photoColumns") if settings else None
    try:
        # Clamped rather than trusted. The registry declares min 1 / max 4 and the API coerces to
        # that, but this value also arrives from an offline phone whose registry may be older, and
        # a zero here would be a division by zero in the photo grid rather than a validation error.
        columns = max(1, min(4, int(columns))) if columns not in (None, "") else None
    except (TypeError, ValueError):
        columns = None

    if photos is not None or columns is not None:
        sections = [
            replace(
                s,
                **({} if photos is None else {"include_photos": bool(s.include_photos and photos)}),
                **({} if columns is None else {"photo_columns": columns}),
            )
            for s in sections
        ]

    changes: dict[str, Any] = {"sections": tuple(sections)}
    numbered = _asked(settings, "numberHeadings")
    if numbered is not None:
        changes["number_headings"] = numbered

    return replace(template_, **changes)


def template_choices() -> list[dict[str, str]]:
    """The picker's options, for the web and Android template dropdowns."""
    return [{"id": t.id, "name": t.name, "description": t.description} for t in TEMPLATES]
