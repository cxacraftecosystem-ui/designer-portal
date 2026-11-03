package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.data.DwCustomCache
import com.designprototype.workshop.data.DwQuestionnaireCopy
import com.designprototype.workshop.data.DwReportSignOff
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.customSectionsForReport
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

data class ReportPlan(
    val template: ReportTemplate,
    val meta: ReportMeta,
    val theme: ReportTheme,
    val settings: Map<String, JsonElement>,
    val warnings: List<String>,
    val serverCopyUnread: Boolean = false,
    val signOff: DwReportSignOff? = null,
)

internal fun reportPlanFor(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    workshopId: String,
    requestedTemplateId: String,
    requestedAccent: String,
    format: String,
    generatedAt: String,
    serverCopyUnread: Boolean = false,
    questionnaires: DwQuestionnaireCopy = DwQuestionnaireCopy.UNKNOWN,
    customSections: DwCustomCache? = null,
    signOff: DwReportSignOff? = null,
): ReportPlan {
    val settings = draft?.stages?.get("REPORT_GENERATION")?.values.orEmpty()
    val templateId = resolveTemplateId(requestedTemplateId, settings, draft?.templateId.orEmpty())

    val catalogue = withUnknownStages(reportTemplate(templateId), schema)
    val template = applyReportSettings(
        catalogue,
        settings,
        customSections = customSectionsForReport(customSections) { stageKey ->
            draft?.stages?.get(stageKey)?.custom.orEmpty()
        },
    )

    val setup = draft?.stages?.get("WORKSHOP_SETUP")?.values.orEmpty()
    fun setupText(key: String) = DwValues.text(setup[key]).trim()
    val record = ReportRecord(
        id = workshopId,
        title = draft?.title?.takeIf { it.isNotBlank() } ?: "Design & Prototype Workshop",
        craftName = setupText("craftName"),
        clusterName = setupText("clusterName"),
        state = setupText("state"),
        designerName = setupText("designerName"),
        implementingAgency = setupText("implementingAgency"),
        workshopCode = setupText("workshopCode"),
    )

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
        signOff = signOff,
    )
}

private fun withUnknownStages(template: ReportTemplate, schema: SchemaResponse): ReportTemplate {
    val unknown = unknownStages(schema)
    if (unknown.isEmpty()) return template
    return template.copy(
        sections = template.sections + unknown.map {
            TemplateSection(stageKey = it, presentation = Presentation.AUTO)
        }
    )
}

private fun unknownStages(schema: SchemaResponse): List<String> =
    schema.stages
        .sortedBy { it.number }
        .map { it.key }
        .filter { it.isNotEmpty() && it !in NARRATIVE_ORDER && it !in NON_PRINTING_STAGES }

private fun unknownStageWarning(schema: SchemaResponse): String? {
    val unknown = unknownStages(schema)
    if (unknown.isEmpty()) return null
    val titles = schema.stages.filter { it.key in unknown }.map { it.title.ifBlank { it.key } }
    return "${plural(unknown.size, "stage")} printed at the end (${titles.joinToString(", ")}). Update the app to fix the order."
}
