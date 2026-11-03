from __future__ import annotations

from collections.abc import Mapping, Sequence
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
    AUTO = "AUTO"


class SpecialSection(str, Enum):
    COVER = "COVER"
    TOC = "TOC"
    ACKNOWLEDGEMENT = "ACKNOWLEDGEMENT"
    SUMMARY_METRICS = "SUMMARY_METRICS"
    SIGNATURES = "SIGNATURES"
    ANNEXURE_MEDIA = "ANNEXURE_MEDIA"
    ANNEXURE_TRANSCRIPTS = "ANNEXURE_TRANSCRIPTS"
    ANNEXURE_QUESTIONNAIRES = "ANNEXURE_QUESTIONNAIRES"
    ANNEXURE_AI_LAYERS = "ANNEXURE_AI_LAYERS"
    CUSTOM_SECTION = "CUSTOM_SECTION"
    COMPLETENESS = "COMPLETENESS"
    MAP = "MAP"
    CHART = "CHART"


@dataclass(frozen=True, slots=True)
class TemplateSection:
    stage_key: str = ""
    special: SpecialSection | None = None
    heading: str = ""
    presentation: Presentation = Presentation.AUTO
    include_photos: bool = True
    photo_columns: int = 2
    max_photos: int = 0
    page_break_before: bool = False
    omit_if_empty: bool = True
    intro: str = ""
    entities: tuple[str, ...] = ()
    include_figures: bool = True
    figures: tuple[str, ...] = ()
    custom_key: str = ""

    def __post_init__(self) -> None:
        if bool(self.stage_key) == bool(self.special):
            raise ValueError("a TemplateSection needs exactly one of stage_key or special")
        if (self.special is SpecialSection.CUSTOM_SECTION) != bool(self.custom_key):
            raise ValueError(
                "a CUSTOM_SECTION names exactly one custom_key, and only a CUSTOM_SECTION may "
                "carry one"
            )


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
    show_empty_note: bool = True

    def section_for(self, stage_key: str) -> TemplateSection | None:
        return next((s for s in self.sections if s.stage_key == stage_key), None)


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

NON_PRINTING_STAGES: frozenset[str] = frozenset({"REPORT_GENERATION", "DATA_QUALITY_ARCHIVE"})

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

_HEAVY_STAGES = frozenset(
    {
        "PROTOTYPE_DEVELOPMENT",
        "FINAL_PROTOTYPE_DOCUMENTATION",
        "SKETCH_DEVELOPMENT",
        "MARKET_SURVEY_CAPTURE",
    }
)


MAP_SECTION = TemplateSection(
    special=SpecialSection.MAP,
    heading="Where the workshop was held and where its artisans live",
)

OUTCOME_FIGURES = TemplateSection(
    special=SpecialSection.CHART,
    heading="Outcomes in figures",
    figures=("OUTPUT_COUNTS", "PROTOTYPE_STATUS"),
)


def _standard_sections(
    *, photos: bool = True, photo_columns: int = 2, figures: bool = False
) -> tuple[TemplateSection, ...]:
    sections: list[TemplateSection] = [
        TemplateSection(special=SpecialSection.COVER),
        TemplateSection(special=SpecialSection.TOC),
        TemplateSection(special=SpecialSection.SUMMARY_METRICS, heading="Workshop at a glance"),
    ]
    if figures:
        sections.append(OUTCOME_FIGURES)
    for stage_key in NARRATIVE_ORDER:
        sections.append(
            TemplateSection(
                stage_key=stage_key,
                presentation=_TABULAR.get(stage_key, Presentation.AUTO),
                include_photos=photos,
                photo_columns=photo_columns,
                page_break_before=stage_key in _HEAVY_STAGES,
            )
        )
        if figures and stage_key == "WORKSHOP_PLAN_PARTICIPANTS_OPENING":
            sections.append(MAP_SECTION)
    sections.append(TemplateSection(special=SpecialSection.SIGNATURES, page_break_before=True))
    return tuple(sections)


TRANSCRIPT_ANNEXURE = TemplateSection(
    special=SpecialSection.ANNEXURE_TRANSCRIPTS,
    heading="Annexure — Recordings and transcripts",
    page_break_before=True,
)

QUESTIONNAIRE_ANNEXURE = TemplateSection(
    special=SpecialSection.ANNEXURE_QUESTIONNAIRES,
    heading="Annexure — Questionnaire responses",
    page_break_before=True,
)


DCH_THEME = ReportTheme(
    accent="1F3864",
    accent_soft="2F5496",
    table_header_fill="1F3864",
    zebra_fill="F2F5FA",
    rule="B8C4D9",
)
DIC_THEME = ReportTheme(
    accent="1B4332",
    accent_soft="2D6A4F",
    table_header_fill="1B4332",
    zebra_fill="EFF6F1",
    rule="BBD3C4",
)
AGENCY_THEME = ReportTheme(
    accent="6B2737",
    accent_soft="8C3A4D",
    table_header_fill="6B2737",
    zebra_fill="FBF1F3",
    rule="E0C3CB",
)
CATALOGUE_THEME = ReportTheme(
    accent="1B1B1B",
    accent_soft="4A4A4A",
    table_header_fill="2B2B2B",
    zebra_fill="F5F5F4",
    rule="D6D3D1",
    base_size_pt=10.0,
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
        sections=(*_standard_sections(figures=True), QUESTIONNAIRE_ANNEXURE, TRANSCRIPT_ANNEXURE),
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
        sections=(*_standard_sections(), QUESTIONNAIRE_ANNEXURE, TRANSCRIPT_ANNEXURE),
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
            TemplateSection(special=SpecialSection.SUMMARY_METRICS, heading="Outcomes at a glance"),
            TemplateSection(stage_key="WORKSHOP_OUTCOMES", presentation=Presentation.NARRATIVE),
            TemplateSection(
                stage_key="FINAL_PROTOTYPE_DOCUMENTATION",
                presentation=Presentation.CARDS,
                page_break_before=True,
            ),
            TemplateSection(stage_key="COSTING_MARKET_LINKAGE", presentation=Presentation.TABLE),
            TemplateSection(stage_key="PROTOTYPE_VALIDATION", presentation=Presentation.TABLE),
            TemplateSection(
                stage_key="WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                presentation=Presentation.TABLE,
                page_break_before=True,
                heading="Annexure A — Participants",
            ),
            TemplateSection(
                stage_key="CLUSTER_CRAFT_BACKGROUND",
                presentation=Presentation.KEY_VALUE,
                heading="Annexure B — Cluster and craft background",
            ),
            TemplateSection(
                stage_key="MARKET_SURVEY_CAPTURE",
                presentation=Presentation.TABLE,
                heading="Annexure C — Market survey",
            ),
            TemplateSection(stage_key="POST_WORKSHOP_FOLLOWUP", presentation=Presentation.TABLE),
            TemplateSection(special=SpecialSection.SIGNATURES, page_break_before=True),
            QUESTIONNAIRE_ANNEXURE,
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
            TemplateSection(special=SpecialSection.SUMMARY_METRICS, heading="Workshop at a glance"),
            TemplateSection(stage_key="WORKSHOP_SETUP", presentation=Presentation.KEY_VALUE),
            TemplateSection(
                stage_key="CLUSTER_CRAFT_BACKGROUND",
                presentation=Presentation.NARRATIVE,
                include_photos=False,
            ),
            TemplateSection(
                stage_key="MARKET_ANALYSIS_DIRECTION",
                presentation=Presentation.NARRATIVE,
                include_photos=False,
            ),
            TemplateSection(
                stage_key="DESIGN_BRIEF", presentation=Presentation.NARRATIVE, include_photos=False
            ),
            TemplateSection(
                stage_key="FINAL_PROTOTYPE_DOCUMENTATION",
                presentation=Presentation.TABLE,
                photo_columns=3,
                max_photos=6,
            ),
            TemplateSection(
                stage_key="COSTING_MARKET_LINKAGE",
                presentation=Presentation.TABLE,
                include_photos=False,
            ),
            TemplateSection(
                stage_key="WORKSHOP_OUTCOMES",
                presentation=Presentation.NARRATIVE,
                include_photos=False,
            ),
            TemplateSection(special=SpecialSection.SIGNATURES),
            QUESTIONNAIRE_ANNEXURE,
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
            TemplateSection(
                special=SpecialSection.ANNEXURE_MEDIA,
                page_break_before=True,
                heading="Annexure — Photographic record",
            ),
            QUESTIONNAIRE_ANNEXURE,
            TRANSCRIPT_ANNEXURE,
            TemplateSection(
                special=SpecialSection.COMPLETENESS, heading="Annexure — Data completeness"
            ),
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
            TemplateSection(
                stage_key="FINAL_PROTOTYPE_DOCUMENTATION",
                presentation=Presentation.CARDS,
                photo_columns=1,
                heading="Products",
            ),
            TemplateSection(
                stage_key="COSTING_MARKET_LINKAGE",
                presentation=Presentation.TABLE,
                include_photos=False,
                heading="Price list",
                page_break_before=True,
                include_figures=False,
            ),
            TemplateSection(
                stage_key="WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                presentation=Presentation.TABLE,
                include_photos=False,
                heading="The makers",
            ),
            QUESTIONNAIRE_ANNEXURE,
            TRANSCRIPT_ANNEXURE,
        ),
        number_headings=False,
        theme=CATALOGUE_THEME,
    ),
)


def template(template_id: str) -> ReportTemplate:
    found = next((t for t in TEMPLATES if t.id == template_id), None)
    return found or TEMPLATES[0]


_SECTION_TOGGLES: dict[str, SpecialSection] = {
    "includeTableOfContents": SpecialSection.TOC,
    "includeMediaAnnexure": SpecialSection.ANNEXURE_MEDIA,
    "includeCompletenessAnnexure": SpecialSection.COMPLETENESS,
}


def _asked(settings: Mapping[str, Any] | None, key: str) -> bool | None:
    if not settings:
        return None
    raw = settings.get(key)
    if raw is None or raw == "":
        return None
    return bool(raw)


_TOGGLE_LABELS: dict[str, str] = {
    "includeTableOfContents": "Include a table of contents",
    "includeMediaAnnexure": "Include the photographic annexure",
    "includeCompletenessAnnexure": "Include the completeness annexure",
}


def inert_section_toggles(
    template_: ReportTemplate, settings: Mapping[str, Any] | None
) -> tuple[str, ...]:
    if not settings:
        return ()
    present = {s.special for s in template_.sections}
    out: list[str] = []
    for key, special in _SECTION_TOGGLES.items():
        if _asked(settings, key) is True and special not in present:
            out.append(
                f"“{_TOGGLE_LABELS[key]}” is on, but the {template_.name} template does not carry "
                f"that section and this switch can only leave one out — the file was generated "
                f"without it. Choose a template that includes it, or turn the switch off."
            )
    return tuple(out)


def apply_report_settings(
    template_: ReportTemplate,
    settings: Mapping[str, Any] | None,
    *,
    include_photographs: bool | None = None,
    include_ai_layers: bool | None = None,
    custom_sections: Sequence[Any] = (),
) -> ReportTemplate:
    if (
        not settings
        and include_photographs is None
        and not include_ai_layers
        and not custom_sections
    ):
        return template_

    sections = list(template_.sections)

    for key, special in _SECTION_TOGGLES.items():
        if _asked(settings, key) is False:
            sections = [s for s in sections if s.special is not special]

    excluded = settings.get("excludedStages") if settings else None
    if isinstance(excluded, (list, tuple)):
        drop = {str(k).strip() for k in excluded if str(k).strip()}
        if drop:
            sections = [s for s in sections if not (s.stage_key and s.stage_key in drop)]

    if custom_sections:
        ordered = sorted(
            custom_sections,
            key=lambda s: (int(getattr(s, "sort_order", 0) or 0), str(getattr(s, "key", ""))),
        )
        for definition in ordered:
            key = str(getattr(definition, "key", "") or "")
            if not key:
                continue
            block = TemplateSection(
                special=SpecialSection.CUSTOM_SECTION,
                custom_key=key,
                heading=str(getattr(definition, "title", "") or ""),
            )
            stage_key = str(getattr(definition, "stage_key", "") or "")
            anchor = next(
                (i for i, s in enumerate(sections) if s.stage_key and s.stage_key == stage_key),
                None,
            )
            if anchor is not None:
                at = anchor + 1
                while at < len(sections) and sections[at].special is SpecialSection.CUSTOM_SECTION:
                    at += 1
                sections.insert(at, block)
                continue
            at = next(
                (i for i, s in enumerate(sections) if s.special is SpecialSection.COMPLETENESS),
                len(sections),
            )
            sections.insert(at, replace(block, page_break_before=True))

    if include_ai_layers:
        annexure = TemplateSection(
            special=SpecialSection.ANNEXURE_AI_LAYERS,
            page_break_before=True,
        )
        at = next(
            (i for i, s in enumerate(sections) if s.special is SpecialSection.COMPLETENESS),
            len(sections),
        )
        sections.insert(at, annexure)

    photos = include_photographs
    if photos is None:
        photos = _asked(settings, "includePhotographs")

    columns = settings.get("photoColumns") if settings else None
    try:
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
    return [{"id": t.id, "name": t.name, "description": t.description} for t in TEMPLATES]
