package com.designprototype.workshop.report

import com.designprototype.workshop.data.DwCustomSectionDto
import com.designprototype.workshop.data.DwTier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Presentation {
    KEY_VALUE, NARRATIVE, TABLE, CARDS, GALLERY,

    AUTO,
}

enum class SpecialSection {
    COVER,
    TOC,
    ACKNOWLEDGEMENT,
    SUMMARY_METRICS,
    SIGNATURES,
    ANNEXURE_MEDIA,
    ANNEXURE_TRANSCRIPTS,

    ANNEXURE_AI_LAYERS,

    ANNEXURE_QUESTIONNAIRES,
    COMPLETENESS,
    MAP,
    CHART,

    CUSTOM_SECTION,
}

data class TemplateSection(
    val stageKey: String = "",
    val special: SpecialSection? = null,
    val heading: String = "",
    val presentation: Presentation = Presentation.AUTO,
    val includePhotos: Boolean = true,
    val photoColumns: Int = 2,
    val maxPhotos: Int = 0,
    val pageBreakBefore: Boolean = false,
    val omitIfEmpty: Boolean = true,
    val intro: String = "",
    val entities: List<String> = emptyList(),
    val includeFigures: Boolean = true,
    val figures: List<String> = emptyList(),
    val customKey: String = "",
) {
    init {
        require(stageKey.isNotEmpty() != (special != null)) {
            "a TemplateSection needs exactly one of stageKey or special"
        }
        require((special == SpecialSection.CUSTOM_SECTION) == customKey.isNotEmpty()) {
            "a CUSTOM_SECTION names exactly one customKey, and only a CUSTOM_SECTION may carry one"
        }
    }
}

data class ReportTemplate(
    val id: String,
    val name: String,
    val description: String,
    val sections: List<TemplateSection>,
    val maxTier: DwTier = DwTier.ADVANCED,
    val pageSize: PageSize = PageSize.A4,
    val theme: ReportTheme = ReportTheme(),
    val numberHeadings: Boolean = true,
    val organisation: String = "",
    val showEmptyNote: Boolean = true,
) {
    fun sectionFor(stageKey: String): TemplateSection? = sections.firstOrNull { it.stageKey == stageKey }
}

val NARRATIVE_ORDER: List<String> = listOf(
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

val NON_PRINTING_STAGES: Set<String> = setOf("REPORT_GENERATION", "DATA_QUALITY_ARCHIVE")

private val TABULAR: Map<String, Presentation> = mapOf(
    "WORKSHOP_PLAN_PARTICIPANTS_OPENING" to Presentation.TABLE,
    "SURVEY_PLANNING" to Presentation.TABLE,
    "MARKET_SURVEY_CAPTURE" to Presentation.TABLE,
    "SKETCH_DEVELOPMENT" to Presentation.CARDS,
    "SKETCH_REVIEW" to Presentation.TABLE,
    "PROTOTYPE_DEVELOPMENT" to Presentation.CARDS,
    "PROTOTYPE_ITERATION" to Presentation.TABLE,
    "PROTOTYPE_VALIDATION" to Presentation.TABLE,
    "FINAL_PROTOTYPE_DOCUMENTATION" to Presentation.CARDS,
    "COSTING_MARKET_LINKAGE" to Presentation.TABLE,
    "POST_WORKSHOP_FOLLOWUP" to Presentation.TABLE,
)

private val HEAVY_STAGES: Set<String> = setOf(
    "PROTOTYPE_DEVELOPMENT", "FINAL_PROTOTYPE_DOCUMENTATION", "SKETCH_DEVELOPMENT",
    "MARKET_SURVEY_CAPTURE",
)

val MAP_SECTION = TemplateSection(
    special = SpecialSection.MAP,
    heading = "Where the workshop was held and where its artisans live",
)

val OUTCOME_FIGURES = TemplateSection(
    special = SpecialSection.CHART,
    heading = "Outcomes in figures",
    figures = listOf("OUTPUT_COUNTS", "PROTOTYPE_STATUS"),
)

val TRANSCRIPT_ANNEXURE = TemplateSection(
    special = SpecialSection.ANNEXURE_TRANSCRIPTS,
    heading = "Annexure — Recordings and transcripts",
    pageBreakBefore = true,
)

val QUESTIONNAIRE_ANNEXURE = TemplateSection(
    special = SpecialSection.ANNEXURE_QUESTIONNAIRES,
    heading = "Annexure — Questionnaire responses",
    pageBreakBefore = true,
)

private fun standardSections(
    photos: Boolean = true,
    photoColumns: Int = 2,
    figures: Boolean = false,
): List<TemplateSection> {
    val sections = ArrayList<TemplateSection>()
    sections += TemplateSection(special = SpecialSection.COVER)
    sections += TemplateSection(special = SpecialSection.TOC)
    sections += TemplateSection(special = SpecialSection.SUMMARY_METRICS, heading = "Workshop at a glance")
    if (figures) sections += OUTCOME_FIGURES
    for (stageKey in NARRATIVE_ORDER) {
        sections += TemplateSection(
            stageKey = stageKey,
            presentation = TABULAR[stageKey] ?: Presentation.AUTO,
            includePhotos = photos,
            photoColumns = photoColumns,
            pageBreakBefore = stageKey in HEAVY_STAGES,
        )
        if (figures && stageKey == "WORKSHOP_PLAN_PARTICIPANTS_OPENING") sections += MAP_SECTION
    }
    sections += TemplateSection(special = SpecialSection.SIGNATURES, pageBreakBefore = true)
    return sections
}

val DCH_THEME = ReportTheme(
    accent = "1F3864", accentSoft = "2F5496", tableHeaderFill = "1F3864",
    zebraFill = "F2F5FA", rule = "B8C4D9",
)
val DIC_THEME = ReportTheme(
    accent = "1B4332", accentSoft = "2D6A4F", tableHeaderFill = "1B4332",
    zebraFill = "EFF6F1", rule = "BBD3C4",
)
val AGENCY_THEME = ReportTheme(
    accent = "6B2737", accentSoft = "8C3A4D", tableHeaderFill = "6B2737",
    zebraFill = "FBF1F3", rule = "E0C3CB",
)
val CATALOGUE_THEME = ReportTheme(
    accent = "1B1B1B", accentSoft = "4A4A4A", tableHeaderFill = "2B2B2B",
    zebraFill = "F5F5F4", rule = "D6D3D1", baseSizePt = 10.0f,
)

val REPORT_TEMPLATES: List<ReportTemplate> = listOf(
    ReportTemplate(
        id = "DCH_STANDARD",
        name = "DCH standard workshop report",
        description = "The full narrative report for submission to the Development Commissioner " +
            "(Handicrafts): cover, contents, every stage in the reader's order, photographs, " +
            "cost sheets and sign-off.",
        sections = standardSections(figures = true) + QUESTIONNAIRE_ANNEXURE + TRANSCRIPT_ANNEXURE,
        organisation = "Office of the Development Commissioner (Handicrafts)",
        theme = DCH_THEME,
    ),
    ReportTemplate(
        id = "DIC_STANDARD",
        name = "DIC standard workshop report",
        description = "The District Industries Centre format. The same content as the DCH report " +
            "with the section names a DIC submission expects and the administrative annexure " +
            "brought forward.",
        sections = standardSections() + QUESTIONNAIRE_ANNEXURE + TRANSCRIPT_ANNEXURE,
        organisation = "District Industries Centre",
        theme = DIC_THEME,
    ),
    ReportTemplate(
        id = "IMPLEMENTING_AGENCY",
        name = "Implementing agency format",
        description = "For the implementing agency's own file: outcomes, costs and prototypes " +
            "first, with the cluster and survey background reduced to an annexure.",
        sections = listOf(
            TemplateSection(special = SpecialSection.COVER),
            TemplateSection(special = SpecialSection.TOC),
            TemplateSection(special = SpecialSection.SUMMARY_METRICS, heading = "Outcomes at a glance"),
            TemplateSection(stageKey = "WORKSHOP_OUTCOMES", presentation = Presentation.NARRATIVE),
            TemplateSection(
                stageKey = "FINAL_PROTOTYPE_DOCUMENTATION",
                presentation = Presentation.CARDS, pageBreakBefore = true,
            ),
            TemplateSection(stageKey = "COSTING_MARKET_LINKAGE", presentation = Presentation.TABLE),
            TemplateSection(stageKey = "PROTOTYPE_VALIDATION", presentation = Presentation.TABLE),
            TemplateSection(
                stageKey = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                presentation = Presentation.TABLE, pageBreakBefore = true,
                heading = "Annexure A — Participants",
            ),
            TemplateSection(
                stageKey = "CLUSTER_CRAFT_BACKGROUND",
                presentation = Presentation.KEY_VALUE,
                heading = "Annexure B — Cluster and craft background",
            ),
            TemplateSection(
                stageKey = "MARKET_SURVEY_CAPTURE",
                presentation = Presentation.TABLE,
                heading = "Annexure C — Market survey",
            ),
            TemplateSection(stageKey = "POST_WORKSHOP_FOLLOWUP", presentation = Presentation.TABLE),
            TemplateSection(special = SpecialSection.SIGNATURES, pageBreakBefore = true),
            QUESTIONNAIRE_ANNEXURE,
            TRANSCRIPT_ANNEXURE,
        ),
        theme = AGENCY_THEME,
    ),
    ReportTemplate(
        id = "COMPACT_SUMMARY",
        name = "Compact summary",
        description = "A few pages for a review meeting: what was done, what came out of it and " +
            "what it cost. Basic-tier fields only, one photograph per prototype.",
        maxTier = DwTier.BASIC,
        sections = listOf(
            TemplateSection(special = SpecialSection.COVER),
            TemplateSection(special = SpecialSection.SUMMARY_METRICS, heading = "Workshop at a glance"),
            TemplateSection(stageKey = "WORKSHOP_SETUP", presentation = Presentation.KEY_VALUE),
            TemplateSection(
                stageKey = "CLUSTER_CRAFT_BACKGROUND",
                presentation = Presentation.NARRATIVE, includePhotos = false,
            ),
            TemplateSection(
                stageKey = "MARKET_ANALYSIS_DIRECTION",
                presentation = Presentation.NARRATIVE, includePhotos = false,
            ),
            TemplateSection(
                stageKey = "DESIGN_BRIEF", presentation = Presentation.NARRATIVE,
                includePhotos = false,
            ),
            TemplateSection(
                stageKey = "FINAL_PROTOTYPE_DOCUMENTATION",
                presentation = Presentation.TABLE, photoColumns = 3, maxPhotos = 6,
            ),
            TemplateSection(
                stageKey = "COSTING_MARKET_LINKAGE", presentation = Presentation.TABLE,
                includePhotos = false,
            ),
            TemplateSection(
                stageKey = "WORKSHOP_OUTCOMES", presentation = Presentation.NARRATIVE,
                includePhotos = false,
            ),
            TemplateSection(special = SpecialSection.SIGNATURES),
            QUESTIONNAIRE_ANNEXURE,
            TRANSCRIPT_ANNEXURE,
        ),
        theme = DCH_THEME,
    ),
    ReportTemplate(
        id = "DETAILED_TECHNICAL",
        name = "Detailed technical report",
        description = "Everything captured, including the Advanced tier: process sequences, " +
            "material specifications, iteration histories, quality assessments and the full media " +
            "annexure. The archival copy.",
        sections = standardSections(photoColumns = 2, figures = true) + listOf(
            TemplateSection(
                special = SpecialSection.ANNEXURE_MEDIA, pageBreakBefore = true,
                heading = "Annexure — Photographic record",
            ),
            QUESTIONNAIRE_ANNEXURE,
            TRANSCRIPT_ANNEXURE,
            TemplateSection(
                special = SpecialSection.COMPLETENESS,
                heading = "Annexure — Data completeness",
            ),
        ),
        theme = DCH_THEME,
    ),
    ReportTemplate(
        id = "PHOTO_CATALOGUE",
        name = "Photo catalogue",
        description = "A buyer-facing catalogue: the final products, large, with their dimensions, " +
            "materials and prices. Almost no prose.",
        sections = listOf(
            TemplateSection(special = SpecialSection.COVER),
            TemplateSection(
                stageKey = "FINAL_PROTOTYPE_DOCUMENTATION",
                presentation = Presentation.CARDS, photoColumns = 1,
                heading = "Products",
            ),
            TemplateSection(
                stageKey = "COSTING_MARKET_LINKAGE",
                presentation = Presentation.TABLE, includePhotos = false,
                heading = "Price list", pageBreakBefore = true,
                includeFigures = false,
            ),
            TemplateSection(
                stageKey = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                presentation = Presentation.TABLE, includePhotos = false,
                heading = "The makers",
            ),
            QUESTIONNAIRE_ANNEXURE,
            TRANSCRIPT_ANNEXURE,
        ),
        numberHeadings = false,
        theme = CATALOGUE_THEME,
    ),
)

fun reportTemplate(templateId: String): ReportTemplate =
    REPORT_TEMPLATES.firstOrNull { it.id == templateId } ?: REPORT_TEMPLATES.first()

data class TemplateChoice(val id: String, val name: String, val description: String)

fun templateChoices(): List<TemplateChoice> =
    REPORT_TEMPLATES.map { TemplateChoice(it.id, it.name, it.description) }

private val SECTION_TOGGLES: Map<String, SpecialSection> = mapOf(
    "includeTableOfContents" to SpecialSection.TOC,
    "includeMediaAnnexure" to SpecialSection.ANNEXURE_MEDIA,
    "includeCompletenessAnnexure" to SpecialSection.COMPLETENESS,
)

internal fun askedSetting(settings: Map<String, JsonElement>?, key: String): Boolean? {
    if (settings.isNullOrEmpty()) return null
    val raw = settings[key] ?: return null
    return when (raw) {
        is JsonNull -> null
        is JsonArray -> raw.isNotEmpty()
        is JsonObject -> raw.isNotEmpty()
        is JsonPrimitive -> when {
            raw.isString -> if (raw.content.isEmpty()) null else true
            raw.content == "true" -> true
            raw.content == "false" -> false
            else -> raw.content.toDoubleOrNull()?.let { it != 0.0 } ?: raw.content.isNotEmpty()
        }
    }
}

private fun photoColumns(settings: Map<String, JsonElement>?): Int? {
    val raw = settings?.get("photoColumns") ?: return null
    if (raw !is JsonPrimitive || raw is JsonNull) return null
    val text = raw.content
    if (text.isEmpty()) return null
    val parsed = if (raw.isString) {
        text.trim().toLongOrNull()?.toDouble() ?: return null
    } else {
        when (text) {
            "true" -> 1.0
            "false" -> 0.0
            else -> text.toDoubleOrNull() ?: return null
        }
    }
    if (!parsed.isFinite()) return null
    val truncated = parsed.toLong()
    return truncated.coerceIn(1L, 4L).toInt()
}

fun applyReportSettings(
    template: ReportTemplate,
    settings: Map<String, JsonElement>?,
    includePhotographs: Boolean? = null,
    customSections: List<DwCustomSectionDto> = emptyList(),
): ReportTemplate {
    if (settings.isNullOrEmpty() && includePhotographs == null && customSections.isEmpty()) return template

    var sections = template.sections

    for ((key, special) in SECTION_TOGGLES) {
        if (askedSetting(settings, key) == false) sections = sections.filter { it.special != special }
    }

    val excluded = settings?.get("excludedStages")
    if (excluded is JsonArray) {
        val drop = excluded.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        if (drop.isNotEmpty()) {
            sections = sections.filter { !(it.stageKey.isNotEmpty() && it.stageKey in drop) }
        }
    }

    if (customSections.isNotEmpty()) {
        val ordered = customSections.sortedWith(compareBy({ it.sortOrder }, { it.key }))
        val spliced = sections.toMutableList()
        ordered.forEach { definition ->
            val key = definition.key
            if (key.isEmpty()) return@forEach
            val block = TemplateSection(
                special = SpecialSection.CUSTOM_SECTION,
                customKey = key,
                heading = definition.title,
            )
            val anchor = spliced.indexOfFirst {
                it.stageKey.isNotEmpty() && it.stageKey == definition.stageKey
            }
            if (anchor >= 0) {
                var at = anchor + 1
                while (at < spliced.size && spliced[at].special == SpecialSection.CUSTOM_SECTION) at++
                spliced.add(at, block)
            } else {
                val at = spliced.indexOfFirst { it.special == SpecialSection.COMPLETENESS }
                spliced.add(
                    if (at >= 0) at else spliced.size,
                    block.copy(pageBreakBefore = true),
                )
            }
        }
        sections = spliced
    }

    val photos = includePhotographs ?: askedSetting(settings, "includePhotographs")
    val columns = photoColumns(settings)

    if (photos != null || columns != null) {
        sections = sections.map { section ->
            section.copy(
                includePhotos = if (photos == null) section.includePhotos else section.includePhotos && photos,
                photoColumns = columns ?: section.photoColumns,
            )
        }
    }

    val numbered = askedSetting(settings, "numberHeadings")
    return template.copy(
        sections = sections,
        numberHeadings = numbered ?: template.numberHeadings,
    )
}
