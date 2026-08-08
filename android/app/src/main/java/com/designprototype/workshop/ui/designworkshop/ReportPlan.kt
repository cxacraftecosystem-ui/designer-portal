package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwQuestionnaireCopy
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.NARRATIVE_ORDER
import com.designprototype.workshop.report.NON_PRINTING_STAGES
import com.designprototype.workshop.report.Presentation
import com.designprototype.workshop.report.ReportMeta
import com.designprototype.workshop.report.ReportRecord
import com.designprototype.workshop.report.ReportTemplate
import com.designprototype.workshop.report.ReportTheme
import com.designprototype.workshop.report.TemplateSection
import com.designprototype.workshop.report.applyReportSettings
import com.designprototype.workshop.report.reportMetaFor
import com.designprototype.workshop.report.reportTemplate
import com.designprototype.workshop.report.reportWarnings
import com.designprototype.workshop.report.resolveAccent
import com.designprototype.workshop.report.resolveFont
import com.designprototype.workshop.report.resolveTemplateId
import com.designprototype.workshop.report.themeFromAccent
import kotlinx.serialization.json.JsonElement

/**
 * Everything the designer's stage-20 answers decide about one export, resolved once.
 *
 * WHY IT IS ONE OBJECT AND NOT SIX CALLS. The server's own comment on `_SECTION_TOGGLES` records
 * what happens otherwise: three call sites each decided for themselves which stage-20 keys mattered
 * and they disagreed, so the preview and the download came out different shapes and the designer
 * approved one document and submitted another. Here the screen needs the warnings BEFORE it renders
 * and the builder needs the template, the palette and the cover WHILE it renders; resolving twice is
 * how those two answers come apart. [reportPlanFor] is the single resolution and both read it.
 */
data class ReportPlan(
    val template: ReportTemplate,
    val meta: ReportMeta,
    val theme: ReportTheme,
    /** Stage 20's saved entry, verbatim, for the cover fields the builder still has to read. */
    val settings: Map<String, JsonElement>,
    /** Advisory, dismissible, shown beside the file — never written into it. */
    val warnings: List<String>,
    /**
     * WHETHER THIS FILE WAS BUILT WITHOUT THE SERVER'S COPY OF THE WORKSHOP — see [ReportSource].
     *
     * The one fact on this object that goes into the DOCUMENT rather than beside it, and it belongs
     * there for the reason `fieldCopyNote` already gives about the office's extra sections: the
     * warnings are read by the designer on the day and dismissed, and the person who has to know
     * that this copy could be short is the officer opening the .docx next month, who was never on
     * the screen that said so. A short report that is silent about being short is the defect this
     * whole path exists to close; the screen notice closes it for the designer and this closes it
     * for everybody downstream of them.
     *
     * False whenever the server answered — which is the ordinary case, and prints nothing.
     */
    val serverCopyUnread: Boolean = false,
)

/**
 * Resolve the template, the palette, the cover and the warnings for one export.
 *
 * The precedence is the server's, three times over: the request wins where it said anything, the
 * saved stage-20 answer applies otherwise, and where neither spoke the template's own value stands.
 *
 * [requestedTemplateId] is the picker's current choice, which is a request in exactly the server's
 * sense — a designer producing one file in a different format without editing their saved answer.
 * [requestedAccent] is the colour chip on the same screen and is read the same way.
 *
 * [format] is "PDF" or "DOCX" and only affects the WARNINGS: a typeface reaches the .docx and
 * cannot reach a PDF drawn on a handset, and saying so for the format that is actually being
 * written is the difference between a useful warning and noise on every export.
 */
internal fun reportPlanFor(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    workshopId: String,
    requestedTemplateId: String,
    requestedAccent: String,
    format: String,
    generatedAt: String,
    /** See [ReportPlan.serverCopyUnread]. Defaulted false so no existing caller has to change. */
    serverCopyUnread: Boolean = false,
    /**
     * What this device holds about this workshop's questionnaires.
     *
     * It reaches the plan for one reason: the questionnaire annexure's warning is the only one whose
     * truth depends on something outside the registry and the stage-20 entry, and the plan is where
     * this screen's two answers — what it TELLS the designer and what it WRITES — are kept from
     * coming apart. Defaulted, so every existing caller and every test keeps the conservative
     * "we have not looked" answer rather than silently claiming an annexure it cannot draw.
     */
    questionnaires: DwQuestionnaireCopy = DwQuestionnaireCopy.UNKNOWN,
): ReportPlan {
    val settings = draft?.stages?.get("REPORT_GENERATION")?.values.orEmpty()
    val templateId = resolveTemplateId(requestedTemplateId, settings, draft?.templateId.orEmpty())

    // The stages this build's catalogue has never heard of are appended BEFORE the settings are
    // applied, so `excludedStages` can still exclude one and `includePhotographs` still reaches it.
    val catalogue = withUnknownStages(reportTemplate(templateId), schema)
    val template = applyReportSettings(catalogue, settings)

    val setup = draft?.stages?.get("WORKSHOP_SETUP")?.values.orEmpty()
    fun setupText(key: String) = DwValues.text(setup[key]).trim()
    val record = ReportRecord(
        id = workshopId,
        // The same fallback the export has always used. A draft created and never named still has
        // to produce a titled document rather than a blank cover.
        title = draft?.title?.takeIf { it.isNotBlank() } ?: "Design & Prototype Workshop",
        craftName = setupText("craftName"),
        clusterName = setupText("clusterName"),
        state = setupText("state"),
        designerName = setupText("designerName"),
        implementingAgency = setupText("implementingAgency"),
        workshopCode = setupText("workshopCode"),
    )

    // THE COLOUR, RESOLVED THE SAME WAY THE PAPER IS, and derived from the TEMPLATE'S palette rather
    // than from the model's defaults — that is what keeps the photo catalogue's 10 pt body text when
    // a designer recolours it. `themeFromAccent` cannot throw, so a malformed hex costs the designer
    // their chosen colour and nothing else; the report is still produced.
    val accent = resolveAccent(requestedAccent, settings)
    var theme = if (accent != null) themeFromAccent(accent, base = template.theme) else template.theme
    resolveFont(null, settings)?.let { (heading, body) ->
        theme = theme.copy(headingFont = heading, bodyFont = body)
    }

    val warnings = ArrayList<String>()
    warnings += reportWarnings(templateId, template, settings, format, questionnaires)
    unknownStageWarning(schema)?.let { warnings += it }

    return ReportPlan(
        template = template,
        meta = reportMetaFor(record, templateId, settings, generatedAt),
        theme = theme,
        settings = settings,
        warnings = warnings,
        serverCopyUnread = serverCopyUnread,
    )
}

/**
 * The registry stages the shipped template catalogue does not name, appended at the end.
 *
 * WHY THIS EXISTS AND WHY IT IS NOT A DEPARTURE FROM THE SERVER. On the server the registry and the
 * six templates are two files of one deployment and ship together, so every stage is named by
 * `NARRATIVE_ORDER` or by `NON_PRINTING_STAGES` and this function would find nothing. On a handset
 * they do NOT ship together: `designWorkshopSchema` refreshes the registry from the API while the
 * catalogue above is compiled into the APK, so a stage added on the server appears in the phone's
 * registry, is captured by a designer in a cluster, and would then be silently absent from the one
 * document that fieldwork exists to produce.
 *
 * A stage the catalogue KNOWS and a template deliberately omits is left omitted — that is the
 * template working as designed, and the compact summary dropping fifteen stages is the whole reason
 * it is called compact. Only a stage no template could have mentioned is appended, and the designer
 * is told it was, so an unfamiliar section at the end of the report is explained rather than
 * mysterious.
 */
private fun withUnknownStages(template: ReportTemplate, schema: SchemaResponse): ReportTemplate {
    val unknown = unknownStages(schema)
    if (unknown.isEmpty()) return template
    return template.copy(
        sections = template.sections + unknown.map {
            TemplateSection(stageKey = it, presentation = Presentation.AUTO)
        }
    )
}

/** Registry stage keys named by neither the narrative order nor the non-printing set, in stage order. */
private fun unknownStages(schema: SchemaResponse): List<String> =
    schema.stages
        .sortedBy { it.number }
        .map { it.key }
        .filter { it.isNotEmpty() && it !in NARRATIVE_ORDER && it !in NON_PRINTING_STAGES }

private fun unknownStageWarning(schema: SchemaResponse): String? {
    val unknown = unknownStages(schema)
    if (unknown.isEmpty()) return null
    val titles = schema.stages.filter { it.key in unknown }.map { it.title.ifBlank { it.key } }
    return "This workshop has ${unknown.size} stage(s) that are newer than this app's report " +
        "templates (${titles.joinToString(", ")}). They are printed at the end of the report " +
        "rather than in the reader's order; the office's copy will place them properly."
}
