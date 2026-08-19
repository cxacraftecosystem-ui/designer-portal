package com.designprototype.workshop.report

import com.designprototype.workshop.data.DwCustomSectionDto
import com.designprototype.workshop.data.DwTier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The six report templates and the stage-20 answers that reshape them — the port of
 * `backend/app/services/report_templates.py`.
 *
 * WHY THIS FILE EXISTS. The template picker on the report screen worked, listed the correct six
 * names offline, saved the choice, and changed NOTHING about the file that came out: `templateId`
 * reached exactly one line of one writer, `<cp:category>` in the .docx metadata. A designer chose
 * "Photo catalogue" because a buyer asked for one and got the DCH standard report, byte for byte
 * identical to the one they would have got from any other choice. Seventeen further switches on the
 * same stage — the table of contents, whether photographs print at all, how many to a row, whether
 * headings are numbered, which stages to leave out, the page size, the running header and foot —
 * were saved faithfully by the form, synced to the server, and read by nothing on this device. The
 * office generated the same workshop from the same data and got a different document. Two files,
 * one officer's desk.
 *
 * A TEMPLATE IS NOT A LAYOUT, and keeping it that way is what lets one catalogue drive a .docx and a
 * .pdf on a server and on a handset without four copies of the rules. Nothing here names a font, a
 * column width or a page break in millimetres; those belong to [ReportTheme] and to the two writers.
 * What a template declares is which of the 22 stages appear, under what heading, at which capture
 * tier, and in which presentation.
 *
 * THIS IS A PORT AND IT MUST AGREE BY VALUE. `app/src/test/resources/report_templates_pin.json` is
 * dumped from the server's own module and every template, every section and 38 settings cases are
 * diffed against it by `ReportTemplatePinTest`. Change one and the other must change with it, or the
 * phone's report and the office's drift apart in a way only a side-by-side comparison would reveal.
 */

// --------------------------------------------------------------------------------------
// What a section is
// --------------------------------------------------------------------------------------

/**
 * How the same stage data prints. Which one is right depends on the audience, not on the data:
 * an administrative annexure wants a label/value grid, a submitted report wants prose, a
 * participant list wants a table and a product catalogue wants a heading and a photograph per
 * record. `PHOTO_CATALOGUE` and `DETAILED_TECHNICAL` are the same twenty-two stages and differ
 * only in these choices and in the tier cap — that is the whole point of the indirection.
 */
enum class Presentation {
    KEY_VALUE, NARRATIVE, TABLE, CARDS, GALLERY,

    /** Let the builder decide from the fields' `reportRole`. */
    AUTO,
}

/** Sections that are not one of the 22 stages. */
enum class SpecialSection {
    COVER,
    TOC,
    ACKNOWLEDGEMENT,
    SUMMARY_METRICS,
    SIGNATURES,
    ANNEXURE_MEDIA,
    ANNEXURE_TRANSCRIPTS,

    /**
     * Every AI layer a person has ACCEPTED, printed at the back and named as a machine's work.
     *
     * NO TEMPLATE IN THIS CATALOGUE CARRIES IT, on this side or on the server's, and that is the
     * design rather than an omission — see the Python `SpecialSection.ANNEXURE_AI_LAYERS`. The
     * server splices the section in only when a report is explicitly asked for it, so it can only
     * reach a document somebody deliberately requested. This handset never asks.
     *
     * DECLARED HERE ANYWAY, FOR TWO REASONS. The port is a by-value mirror of the server's
     * catalogue and a member missing from this enum is a member `applyReportSettings` could not
     * name if it ever had to. And the sentence in [UNSUPPORTED_SECTIONS] has to exist BEFORE the
     * section can appear, not after: the failure this whole port exists to end is a section
     * silently absent from the field copy, and a device that meets an unknown section for the first
     * time in a courtyard has nothing honest to say about it.
     *
     * IT COULD NOT BE DRAWN HERE IN ANY CASE, and the reason is data rather than rendering: an AI
     * layer is a row on the server carrying which model produced it and who accepted it, and
     * nothing under `data/` holds one. That is the state [ANNEXURE_TRANSCRIPTS] was in for its
     * whole first life, and the sentence written for it is the model followed here.
     */
    ANNEXURE_AI_LAYERS,

    /**
     * Every sitting recorded against a questionnaire the designer built and attached to THIS
     * workshop.
     *
     * DRAWN ON THIS DEVICE, from `DwQuestionnaireStore` — the copy of the answers the handset keeps
     * from every questionnaire it has been shown. It used to sit beside [ANNEXURE_TRANSCRIPTS] as a
     * section declared here and drawn by nothing, and the reason recorded then was the right one: the
     * gap was DATA rather than drawing, because no file under `data/` held a questionnaire answer at
     * all. That is what was built, so the section is real now.
     *
     * ONE STATE STILL CANNOT BE DRAWN and [UNSUPPORTED_SECTIONS] still carries a sentence for it: a
     * handset that has never once read this workshop's questionnaire list holds nothing and cannot
     * tell an unattached workshop from an attached one, so it prints no annexure and says so
     * conditionally. Everywhere else — none attached, or attached with the answers on the device —
     * the section speaks for itself. A section silently missing from the field copy is the divergence
     * this port exists to end.
     */
    ANNEXURE_QUESTIONNAIRES,
    COMPLETENESS,
    MAP,
    CHART,

    /**
     * ONE BLOCK OF QUESTIONS A DESIGNER ADDED TO THIS WORKSHOP, named by [TemplateSection.customKey].
     *
     * NO TEMPLATE IN THE CATALOGUE CARRIES IT and none ever can: which sections exist is a fact about
     * one workshop's own definition, not about a format. [applyReportSettings] splices them in, which
     * is the same shape [ANNEXURE_AI_LAYERS] takes and for the same fixture reason — a workshop with
     * no custom sections gets a byte-identical template, so all 116 pinned sections and all 38 pinned
     * `apply_report_settings` cases stay exactly as they are.
     *
     * ── WHAT DECLARING IT ENDED ──────────────────────────────────────────────────────────────────
     *
     * This member did not exist, and neither did the splice, so the handset had a FOURTH arbiter of
     * where a designer's block prints: `renderStageSection` drew the questions inline, at heading
     * level 2, between the stage's singleton and its collections. The server splices them as level-1
     * sections AFTER the whole stage section, which the Python `apply_report_settings` calls itself
     * "the single arbiter of the running order … made one because three call sites used to
     * disagree". Two consequences, and the second is the serious one:
     *
     *   * the office copy numbers "Loom audit" as its own top-level section with a contents-page
     *     entry; the handset buried it as a sub-heading. Different heading numbers, different
     *     contents page, different pagination for everything after it.
     *   * a section whose ANCHOR STAGE the template does not print vanished completely. The stage
     *     loop is `stages[section.stageKey]?.let { renderStageSection(…) }` and the custom blocks
     *     were resolved INSIDE that call, so PHOTO_CATALOGUE (three of the twenty-two stages) and
     *     any template whose stage-20 `excludedStages` names the anchor produced a .docx with the
     *     designer's own questions absent — with nothing in the file or on the export screen saying
     *     so, because `dwCustomSectionWarnings` warns for UNKNOWN only on the stated ground that a
     *     DEFINED copy prints everything. The server appends exactly those as a back annexure before
     *     the completeness section.
     */
    CUSTOM_SECTION,
}

/**
 * One section of a report. Exactly one of [stageKey] and [special] is set.
 *
 * [heading] overrides the stage's own title where a ministry format insists on its own section
 * names — which they do, and which is most of the difference between the DCH and the DIC template.
 */
data class TemplateSection(
    val stageKey: String = "",
    val special: SpecialSection? = null,
    val heading: String = "",
    val presentation: Presentation = Presentation.AUTO,
    val includePhotos: Boolean = true,
    val photoColumns: Int = 2,
    /** 0 = no cap; a photo catalogue wants every one. */
    val maxPhotos: Int = 0,
    val pageBreakBefore: Boolean = false,
    val omitIfEmpty: Boolean = true,
    /** A fixed sentence printed before the data. */
    val intro: String = "",
    /** Restrict to these entity keys; empty means all. */
    val entities: List<String> = emptyList(),
    /**
     * May this stage section print its own figures. On by default, because a chart that has to be
     * opted into per template is a chart six templates forget. Turned off where an infographic
     * would say something the audience must not be told — see the price list in PHOTO_CATALOGUE.
     */
    val includeFigures: Boolean = true,
    /** For a CHART section: which figures, by the ids the builder's figure catalogue declares. */
    val figures: List<String> = emptyList(),
    /**
     * For a [SpecialSection.CUSTOM_SECTION]: WHICH designer-defined block this prints, by its
     * [com.designprototype.workshop.data.DwCustomSectionDto.key].
     *
     * THE KEY AND NOT THE SECTION ITSELF, which is `TemplateSection.custom_key`'s rule on the server
     * and matters here for a second reason it does not have: a template is a compile-time constant
     * on this device, pinned by value against a 485 KB fixture, and a template that carried loaded
     * ROWS could not be compared to anything. The renderer looks the key up in the definition it was
     * handed, so a definition that changed between the two reads leaves a section naming nothing —
     * which appends nothing, exactly as a stage section with no data does.
     */
    val customKey: String = "",
) {
    init {
        // The server raises the same ValueError. It can only fire on an edit to this file, which is
        // exactly where it is cheap to find out — a section that is neither a stage nor a special
        // renders as nothing at all, silently, in the one document a designer is waiting on.
        require(stageKey.isNotEmpty() != (special != null)) {
            "a TemplateSection needs exactly one of stageKey or special"
        }
        // The server's second `ValueError`, and it is checked in BOTH directions. A CUSTOM_SECTION
        // naming no key prints nothing and says nothing about why; a `customKey` on a stage section
        // is a key that drifted onto the wrong section and would be quietly ignored for ever.
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
    /** Print "Not recorded." rather than leaving a silent gap under a heading. */
    val showEmptyNote: Boolean = true,
) {
    fun sectionFor(stageKey: String): TemplateSection? = sections.firstOrNull { it.stageKey == stageKey }
}

// --------------------------------------------------------------------------------------
// The stage running order
// --------------------------------------------------------------------------------------

/**
 * The reader's order, which is NOT the capture order.
 *
 * The source document's reviewer notes that stages 5 and 6 "may come later when they actually go to
 * the field after market and consumer survey", so the app lets those be filled whenever while the
 * report still prints them where a reader expects them. Capture order is the designer's; this order
 * is the reader's — and the handset, which sorted by stage number, was printing the designer's.
 */
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

/**
 * Stage 20 configures the report and stage 21 records the archive; neither is a section OF the
 * report. Printing them would be the report describing its own generation — which is what every
 * on-device export did: a section headed "Report Generation & Submission" printing the designer's
 * own template choice, accent hex and page size back at the ministry, followed by the export log
 * with its file names and SHA-256 checksums.
 */
val NON_PRINTING_STAGES: Set<String> = setOf("REPORT_GENERATION", "DATA_QUALITY_ARCHIVE")

/** Which stages are naturally a table of records rather than prose. */
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

/**
 * The map, placed immediately after the participant roster in every template that carries it.
 * THAT POSITION IS THE FIGURE'S ARGUMENT: a map of home districts printed anywhere else is a
 * decorative map of India; printed under the table of thirty names it answers the question that
 * table raises, which is where those thirty people came from.
 */
val MAP_SECTION = TemplateSection(
    special = SpecialSection.MAP,
    heading = "Where the workshop was held and where its artisans live",
)

/**
 * The two outcome figures, at the FRONT of the report rather than beside the outcomes stage twenty
 * pages in. An officer who reads three pages of a sixty-page submission must meet the yield of the
 * workshop in one of them.
 */
val OUTCOME_FIGURES = TemplateSection(
    special = SpecialSection.CHART,
    heading = "Outcomes in figures",
    figures = listOf("OUTPUT_COUNTS", "PROTOTYPE_STATUS"),
)

/**
 * The transcript annexure, carried by EVERY template. It is a toggle, not a template choice: a
 * designer who recorded six artisans and wants their words in the report should get them whichever
 * format the office asked for, and having to switch template to reach an appendix would mean
 * switching the whole section list and every heading name with it.
 */
val TRANSCRIPT_ANNEXURE = TemplateSection(
    special = SpecialSection.ANNEXURE_TRANSCRIPTS,
    heading = "Annexure — Recordings and transcripts",
    pageBreakBefore = true,
)

/**
 * The questionnaire annexure, carried by EVERY template, immediately before the transcripts.
 *
 * Mirrors `report_templates.QUESTIONNAIRE_ANNEXURE` on the server field for field — the heading
 * string included, because `ReportTemplatePinTest` diffs the two catalogues by value and a heading
 * that differs by one character is a section the two documents name differently.
 *
 * There is no stage-20 toggle beside `includeTranscripts` for this one, on the server either:
 * attaching a questionnaire to a design workshop is already the designer asking for it. See
 * `backend/app/services/report_questionnaires.py` for the argument in full.
 */
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
            // A stage that fills several pages reads better from the top of one.
            pageBreakBefore = stageKey in HEAVY_STAGES,
        )
        if (figures && stageKey == "WORKSHOP_PLAN_PARTICIPANTS_OPENING") sections += MAP_SECTION
    }
    sections += TemplateSection(special = SpecialSection.SIGNATURES, pageBreakBefore = true)
    return sections
}

// --------------------------------------------------------------------------------------
// The palettes
// --------------------------------------------------------------------------------------
//
// Hand-picked by somebody looking at printed reports, which is why they are constants rather than
// derivations. `themeFromAccent` reproduces the DCH pair to within a hex digit and is what a
// designer's own colour goes through; these are the untouched starting points.

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

// --------------------------------------------------------------------------------------
// The six
// --------------------------------------------------------------------------------------

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
                // NO COST BREAKDOWN IN A DOCUMENT THAT GOES TO A BUYER. This template is the
                // buyer-facing catalogue, and the cost-by-head figure prints the maker's material
                // and labour cost beside the price the buyer is being quoted. Every other template
                // wants that chart; this one would hand the cluster's margin to the person
                // negotiating against it.
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

/**
 * Look up a template, falling back to the DCH standard.
 *
 * Falling back rather than throwing is deliberate: a saved workshop can name a template a later
 * release removed, and losing the ability to print a two-week workshop because its template was
 * retired is not an acceptable outcome in a cluster. The caller records the substitution as an
 * export warning — see [reportPlan].
 */
fun reportTemplate(templateId: String): ReportTemplate =
    REPORT_TEMPLATES.firstOrNull { it.id == templateId } ?: REPORT_TEMPLATES.first()

/** One row of the template picker. */
data class TemplateChoice(val id: String, val name: String, val description: String)

/**
 * The picker's options — the same three strings `GET /design-workshops/templates` serves.
 *
 * Worth having locally rather than only from the API: the registry's `REPORT_TEMPLATE` enum, which
 * is what the offline fallback used, carries the ids and the names but no descriptions, so a
 * designer choosing a format with no signal was choosing between six titles with nothing to say
 * what any of them contains.
 */
fun templateChoices(): List<TemplateChoice> =
    REPORT_TEMPLATES.map { TemplateChoice(it.id, it.name, it.description) }

// --------------------------------------------------------------------------------------
// Applying the stage-20 answers
// --------------------------------------------------------------------------------------

/**
 * The stage-20 answers that shape the TEMPLATE rather than the cover, named once because three call
 * sites on the server each decided for themselves which keys mattered and they disagreed.
 */
private val SECTION_TOGGLES: Map<String, SpecialSection> = mapOf(
    "includeTableOfContents" to SpecialSection.TOC,
    "includeMediaAnnexure" to SpecialSection.ANNEXURE_MEDIA,
    "includeCompletenessAnnexure" to SpecialSection.COMPLETENESS,
)

/**
 * A tri-state read of a stage-20 boolean: true, false, or "the designer never said".
 *
 * The distinction is the whole reason this is not `DwValues.bool(...) == true`. A template that
 * prints a table of contents must keep printing one for every workshop saved before the toggle
 * existed, so ABSENT means "leave the template alone" and only an explicit false may take the
 * section away.
 *
 * TRUTHINESS IS PYTHON'S, DELIBERATELY, INCLUDING THE PART THAT LOOKS WRONG. The server's
 * `_asked` ends in `bool(raw)`, and in Python a non-empty STRING is true — so a value stored as
 * the text "false" (an older client, a hand-edited record, a CSV import) turns the section ON at
 * the office. Reading it as false here would give the designer a report with no table of contents
 * and the officer a report with one, from the same record, which is precisely the divergence this
 * whole file exists to end. The server is the authority; where its arithmetic is surprising the
 * port reproduces the surprise rather than quietly improving on it. `DwValues.bool` is NOT used
 * here for that reason — it reads "false" as false, which is right for a form and wrong for this.
 */
internal fun askedSetting(settings: Map<String, JsonElement>?, key: String): Boolean? {
    if (settings.isNullOrEmpty()) return null
    val raw = settings[key] ?: return null
    return when (raw) {
        is JsonNull -> null
        is JsonArray -> raw.isNotEmpty()
        is JsonObject -> raw.isNotEmpty()
        is JsonPrimitive -> when {
            // `isString` FIRST. kotlinx's `booleanOrNull` answers false for the STRING "false",
            // which is the one place this would silently diverge from `bool("false") is True`.
            raw.isString -> if (raw.content.isEmpty()) null else true
            raw.content == "true" -> true
            raw.content == "false" -> false
            else -> raw.content.toDoubleOrNull()?.let { it != 0.0 } ?: raw.content.isNotEmpty()
        }
    }
}

/**
 * `photoColumns` as the server reads it: `int(value)` clamped to 1..4, or null where it said nothing.
 *
 * CLAMPED RATHER THAN TRUSTED. The registry declares min 1 / max 4 and the API coerces to it, but
 * this value also arrives from an offline phone whose registry may be older, and a zero here is a
 * division by zero in the photo grid rather than a validation error.
 *
 * The two grammars are not the same and the difference is pinned by the case table: Python's
 * `int()` TRUNCATES a float toward zero (3.7 -> 3, where a `roundToInt` port would answer 4) and
 * REFUSES a string that is not an integer literal — `int("3.7")` raises, so " 3 " is 3 and "3.7" is
 * nothing at all. `toDoubleOrNull` on its own would accept both and quietly disagree.
 */
private fun photoColumns(settings: Map<String, JsonElement>?): Int? {
    val raw = settings?.get("photoColumns") ?: return null
    if (raw !is JsonPrimitive || raw is JsonNull) return null
    val text = raw.content
    if (text.isEmpty()) return null
    val parsed = if (raw.isString) {
        // A string goes through int(), which accepts surrounding whitespace and an optional sign
        // and nothing else. A decimal point, a comma or a word is a ValueError and the setting is
        // ignored — the template keeps its own column count.
        text.trim().toLongOrNull()?.toDouble() ?: return null
    } else {
        when (text) {
            // int(True) is 1 and int(False) is 0; the 0 then clamps up to 1, which is what the
            // pinned case "photo columns true" records.
            "true" -> 1.0
            "false" -> 0.0
            else -> text.toDoubleOrNull() ?: return null
        }
    }
    if (!parsed.isFinite()) return null
    // Truncation toward zero, as int() does, BEFORE the clamp.
    val truncated = parsed.toLong()
    return truncated.coerceIn(1L, 4L).toInt()
}

/**
 * Return the template the designer's stage-20 answers actually ask for.
 *
 * THIS FUNCTION IS WHY THOSE ANSWERS ARE NOT DECORATION on this device either. The precedence is
 * the one the rest of the pipeline uses — an explicit request argument wins, the saved stage-20
 * answer applies otherwise, and where neither spoke the template keeps its own value untouched.
 * That last clause is load-bearing: it is what stops this from restyling every report this app has
 * ever generated the moment it ships.
 *
 * [ReportTemplate] is immutable, so this returns a NEW template and never mutates
 * [REPORT_TEMPLATES]. Mutating it would leak one workshop's choices into the next export in the
 * same process — the kind of bug that only reproduces after a designer has generated two reports.
 */
fun applyReportSettings(
    template: ReportTemplate,
    settings: Map<String, JsonElement>?,
    includePhotographs: Boolean? = null,
    /**
     * THIS WORKSHOP'S OWN SECTIONS, and this function is the single arbiter of where each one prints
     * — exactly as it is already the single arbiter of which sections survive.
     *
     * TAKEN AS THE DTO RATHER THAN AS A NARROW VALUE TYPE, which is where this deliberately differs
     * from the server. `apply_report_settings` takes a `Sequence[Any]` and reads four attributes by
     * name, because the Python module must not import `custom_sections`, which reads the database.
     * `DwCustomSections.kt` reads nothing — its own header makes "no Android imports, no I/O" a rule
     * — so the import costs nothing and a second value type here would be one more shape to keep in
     * step with the payload. Only `key`, `title`, `stageKey` and `sortOrder` are read.
     *
     * EMPTY IS THE ORDINARY CASE and returns the template untouched, including the identity return
     * below. That is not a nicety: `report_templates_pin.json` is 485 KB of by-value comparison over
     * 38 calls of this function, none of which passes a definition, and it can only be regenerated
     * inside the API container.
     */
    customSections: List<DwCustomSectionDto> = emptyList(),
): ReportTemplate {
    if (settings.isNullOrEmpty() && includePhotographs == null && customSections.isEmpty()) return template

    var sections = template.sections

    // ── which sections survive ────────────────────────────────────────────────────────────────
    for ((key, special) in SECTION_TOGGLES) {
        if (askedSetting(settings, key) == false) sections = sections.filter { it.special != special }
    }

    // `excludedStages` is a TAGS field, so it arrives as a list of stage keys. Unknown keys are
    // ignored rather than rejected: a phone one release ahead may name a stage this build has not
    // heard of, and refusing the whole report over it would lose the fieldwork.
    val excluded = settings?.get("excludedStages")
    if (excluded is JsonArray) {
        val drop = excluded.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        if (drop.isNotEmpty()) {
            sections = sections.filter { !(it.stageKey.isNotEmpty() && it.stageKey in drop) }
        }
    }

    /*
      ── the designer's own sections, spliced where they were asked ────────────────────────────

      AFTER THE EXCLUSION FILTERING ABOVE AND NOT BEFORE, which is the whole reason this block sits
      here rather than three lines up. A section attached to a stage the designer excluded would
      otherwise be inserted after a stage section that is then removed from under it, leaving the
      designer's questions stranded in the middle of somebody else's narrative with no heading above
      them to say what stage they belong to.

      TWO PLACEMENTS, AND THE SECOND IS NOT A FALLBACK SO MUCH AS THE OTHER HALF OF THE RULE
      (docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §4: "a custom section names the stage section it
      belongs after, and its own annexure if it belongs nowhere"):

        * the template prints that stage  ->  immediately after that stage's section, where a reader
          meets the designer's extra questions in the context they were asked in;
        * it does not (excluded, or a template that never carried the stage — PHOTO_CATALOGUE prints
          three of the twenty-two)  ->  appended at the back as its own annexure, BEFORE the
          completeness section, because completeness is a statement ABOUT the document and belongs
          after everything the document contains.

      THIS HANDSET IMPLEMENTED ONLY THE FIRST HALF, AND NOT EVEN AS A SECTION. `renderStageSection`
      drew the questions inline at level 2 between the stage's singleton and its collections, so the
      second half was not a fallback that fired wrongly — there was no code path to it at all, and a
      buyer-facing PHOTO_CATALOGUE came off the phone with the designer's own questions simply gone.
      See [SpecialSection.CUSTOM_SECTION].

      In `sortOrder` then `key`, so the running order is the designer's own and is stable across two
      reports of one workshop rather than following whatever order the rows came back in.
    */
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
                // AFTER THE BLOCKS ALREADY PLACED FOR THIS STAGE, not immediately after the stage
                // section. Inserting at `anchor + 1` every time REVERSED the designer's own running
                // order for the second and every later section asked at one stage — two sections on
                // stage 13 came out as 2, 1 — which is precisely what the ordering above exists to
                // decide, and it would have been read as the sort order not working at all. A
                // CUSTOM_SECTION can only ever sit directly after the stage it belongs to, so
                // walking past them cannot walk past anybody else's.
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

    // ── how the surviving sections print ──────────────────────────────────────────────────────
    val photos = includePhotographs ?: askedSetting(settings, "includePhotographs")
    val columns = photoColumns(settings)

    if (photos != null || columns != null) {
        sections = sections.map { section ->
            section.copy(
                // AND, not assignment. A section the template deliberately prints without
                // photographs — the buyer-facing price list — must not acquire them because a
                // designer turned photographs on for the rest of the document.
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
