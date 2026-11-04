package com.designprototype.workshop.report

import com.designprototype.workshop.data.DwQuestionnaireCopy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

internal fun settingText(settings: Map<String, JsonElement>?, key: String): String {
    val raw = settings?.get(key) ?: return ""
    if (raw !is JsonPrimitive || raw is JsonNull) return ""
    return raw.content.trim()
}

private fun settingHex(settings: Map<String, JsonElement>?, key: String): String? {
    val raw = settings?.get(key) ?: return null
    if (raw !is JsonPrimitive || raw is JsonNull || !raw.isString) return null
    return normaliseHex(raw.content)
}

val FONT_PRESETS: Map<String, Triple<String, String, String>> = linkedMapOf(
    "DEFAULT" to Triple("Template default", "", ""),
    "CALIBRI" to Triple("Calibri", "Calibri Light", "Calibri"),
    "CAMBRIA" to Triple("Cambria (serif)", "Cambria", "Cambria"),
    "GEORGIA" to Triple("Georgia (serif)", "Georgia", "Georgia"),
    "TIMES" to Triple("Times New Roman (serif)", "Times New Roman", "Times New Roman"),
    "ARIAL" to Triple("Arial", "Arial", "Arial"),
    "VERDANA" to Triple("Verdana", "Verdana", "Verdana"),
    "GARAMOND" to Triple("Garamond (serif)", "Garamond", "Garamond"),
)

const val DEFAULT_FONT: String = "DEFAULT"

fun resolveFont(requested: String?, settings: Map<String, JsonElement>?): Pair<String, String>? {
    val candidates = listOf(requested ?: "", settingText(settings, "fontPreset"))
    for (candidate in candidates) {
        val token = candidate.trim().uppercase(Locale.ROOT)
        if (token.isEmpty() || token == DEFAULT_FONT) continue
        val preset = FONT_PRESETS[token] ?: continue
        if (preset.second.isNotEmpty()) return preset.second to preset.third
    }
    return null
}

fun resolveAccent(requested: String?, settings: Map<String, JsonElement>?): String? {
    normaliseHex(requested)?.let { return it }
    settingHex(settings, "themeAccent")?.let { return it }
    val preset = settings?.get("themePreset")
    if (preset is JsonPrimitive && preset !is JsonNull && preset.isString) {
        ACCENT_PRESETS.firstOrNull { it.key == preset.content }?.let { return it.hex }
    }
    return null
}

fun resolveTemplateId(
    requested: String?,
    settings: Map<String, JsonElement>?,
    recordTemplateId: String,
): String {
    val known = REPORT_TEMPLATES.map { it.id }.toSet()
    for (candidate in listOf(requested ?: "", settingText(settings, "templateId"))) {
        val token = candidate.trim()
        if (token in known) return token
    }
    return recordTemplateId.trim()
}

fun wantsTranscripts(requested: Boolean?, settings: Map<String, JsonElement>?): Boolean {
    if (requested != null) return requested
    return askedSetting(settings, "includeTranscripts") == true
}

data class ReportRecord(
    val id: String,
    val title: String,
    val craftName: String = "",
    val clusterName: String = "",
    val state: String = "",
    val designerName: String = "",
    val implementingAgency: String = "",
    val workshopCode: String = "",
)

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

fun formatReportDate(iso: String): String {
    val parts = iso.trim().take(10).split("-")
    if (parts.size != 3) return cleanText(iso)
    val year = parts[0].trim().toIntOrNull() ?: return cleanText(iso)
    val month = parts[1].trim().toIntOrNull() ?: return cleanText(iso)
    val day = parts[2].trim().toIntOrNull() ?: return cleanText(iso)
    val index = month - 1
    val name = MONTHS.getOrNull(if (index < 0) MONTHS.size + index else index)
        ?: return cleanText(iso)
    return String.format(Locale.ROOT, "%02d %s %d", day, name, year)
}

fun submissionLine(settings: Map<String, JsonElement>?): String {
    val to = settingText(settings, "submittedTo")
    val on = settingText(settings, "submissionDate")
    val whenText = if (on.isNotEmpty()) formatReportDate(on) else ""
    if (to.isNotEmpty() && whenText.isNotEmpty()) return "Submitted to $to on $whenText"
    if (to.isNotEmpty()) return "Submitted to $to"
    return if (whenText.isNotEmpty()) "Submitted on $whenText" else ""
}

fun reportMetaFor(
    record: ReportRecord,
    templateId: String,
    settings: Map<String, JsonElement>?,
    generatedAt: String,
): ReportMeta {
    val template = reportTemplate(templateId)

    val subtitleParts = listOf(record.craftName, record.clusterName, record.state).filter { it.isNotEmpty() }
    val derivedSubtitle = when {
        subtitleParts.size > 1 -> subtitleParts.first() + " — " + subtitleParts.drop(1).joinToString(", ")
        subtitleParts.size == 1 -> subtitleParts.first()
        else -> ""
    }

    val requestedSize = settingText(settings, "pageSize")
    val pageSize = PageSize.entries.firstOrNull { it.name == requestedSize } ?: template.pageSize

    val code = record.workshopCode.ifEmpty { record.id }
    return ReportMeta(
        title = settingText(settings, "reportTitle").ifEmpty { record.title },
        subtitle = settingText(settings, "reportSubtitle").ifEmpty { derivedSubtitle },
        author = record.designerName,
        organisation = settingText(settings, "organisationLine")
            .ifEmpty { record.implementingAgency }
            .ifEmpty { template.organisation },
        templateId = template.id,
        templateName = template.name,
        workshopId = code,
        generatedAt = generatedAt,
        pageSize = pageSize,
        headerText = settingText(settings, "headerText").ifEmpty {
            listOf(record.craftName, record.clusterName).filter { it.isNotEmpty() }.joinToString(" — ")
        },
        footerText = settingText(settings, "footerText").ifEmpty { "${template.name} · $code" },
    )
}

enum class SettingReach {
    APPLIED,

    DOCX_ONLY,

    NOT_ON_DEVICE,
}

data class SettingSupport(val key: String, val reach: SettingReach, val note: String)

val STAGE_20_SETTINGS: List<SettingSupport> = listOf(
    SettingSupport("templateId", SettingReach.APPLIED,
        "Chooses the template: which sections print, in what order, under what heading, at which " +
            "capture tier, with which palette and page size."),
    SettingSupport("pageSize", SettingReach.APPLIED,
        "ReportMeta.pageSize; both writers lay the page out from it."),
    SettingSupport("themePreset", SettingReach.APPLIED,
        "Read only when themeAccent is blank, exactly as the server reads it."),
    SettingSupport("themeAccent", SettingReach.APPLIED,
        "The accent the other seven colours are derived from, through themeFromAccent."),
    SettingSupport("fontPreset", SettingReach.DOCX_ONLY,
        "Written into the .docx as w:rFonts and resolved by Word on the machine that opens it. " +
            "A PDF drawn on a handset can only use the system face — it is the one face on the " +
            "device that carries Odia, Devanagari and the rupee sign — so the PDF is set in it and " +
            "the designer is told rather than handed two files that look nothing alike."),
    SettingSupport("reportTitle", SettingReach.APPLIED,
        "The cover title, the .docx document title and the exported file's name."),
    SettingSupport("reportSubtitle", SettingReach.APPLIED,
        "The cover subtitle, in place of the craft, cluster and state derived from stage 1."),
    SettingSupport("organisationLine", SettingReach.APPLIED, "Printed above the title on the cover."),
    SettingSupport("letterheadText", SettingReach.APPLIED,
        "One cover line per typed line, capped at six so a pasted signature block cannot push the " +
            "title off the page."),
    SettingSupport("logo", SettingReach.APPLIED,
        "Resolved from the workshop's own media copy on this device, so it prints with no network."),
    SettingSupport("includeTableOfContents", SettingReach.APPLIED,
        "An explicit false removes the TOC section; absent leaves the template alone."),
    SettingSupport("includePhotographs", SettingReach.APPLIED,
        "ANDed with each section's own setting, so a template that deliberately prints a price " +
            "list without photographs does not acquire them."),
    SettingSupport("photoColumns", SettingReach.APPLIED, "Clamped to 1..4 and used by every photo grid."),
    SettingSupport("includeMediaAnnexure", SettingReach.APPLIED,
        "The photographic annexure is built here: every photograph in the record, in stage order, " +
            "three across, from the copies already on this device. An explicit false removes the " +
            "section; absent leaves the template alone."),
    SettingSupport("includeTranscripts", SettingReach.APPLIED,
        "The transcript annexure is built here, from the copy of the transcripts DwTranscriptStore " +
            "keeps from the workshop and stage reads this app makes while online. A recording this " +
            "copy does not hold yet is counted on the export screen rather than silently omitted."),
    SettingSupport("includeCompletenessAnnexure", SettingReach.APPLIED,
        "The completeness annexure is built here, from the same scorer the stage screens use, plus " +
            "the reference check only a report can make: a REF whose row was deleted counts as " +
            "unfilled in the annexure so that it agrees with the section that prints that field as " +
            "blank. An explicit false removes the section; absent leaves the template alone."),
    SettingSupport("numberHeadings", SettingReach.APPLIED,
        "Passed to every heading the builder emits, so the numbering matches the office's copy."),
    SettingSupport("excludedStages", SettingReach.APPLIED,
        "Named stage keys lose their section; unknown keys are ignored rather than refused."),
    SettingSupport("headerText", SettingReach.APPLIED, "The running head both writers draw."),
    SettingSupport("footerText", SettingReach.APPLIED, "The running foot both writers draw."),
    SettingSupport("submittedTo", SettingReach.APPLIED, "The cover's submission line."),
    SettingSupport("submissionDate", SettingReach.APPLIED, "The cover's submission line."),
)

val UNSUPPORTED_SECTIONS: Map<SpecialSection, String?> = mapOf(
    SpecialSection.ANNEXURE_QUESTIONNAIRES to
        "If a questionnaire is attached to this workshop, its answers are not in this file. Open " +
            "the questionnaire on this phone once while you have a connection, and its answers are " +
            "included in every report made here afterwards, even offline.",
    SpecialSection.ANNEXURE_AI_LAYERS to null,
)

internal fun unsupportedSectionsIn(
    template: ReportTemplate,
    settings: Map<String, JsonElement>?,
    questionnaires: DwQuestionnaireCopy = DwQuestionnaireCopy.UNKNOWN,
): List<SpecialSection> {
    val found = ArrayList<SpecialSection>()
    for (section in template.sections) {
        val special = section.special ?: continue
        if (special !in UNSUPPORTED_SECTIONS) continue
        if (special == SpecialSection.ANNEXURE_QUESTIONNAIRES &&
            questionnaires != DwQuestionnaireCopy.UNKNOWN
        ) continue
        if (special !in found) found += special
    }
    return found
}

fun fieldCopyNote(
    template: ReportTemplate,
    settings: Map<String, JsonElement>?,
    generatedOn: String,
    serverCopyUnread: Boolean = false,
): String {
    val stamp = if (generatedOn.isBlank()) "" else " on $generatedOn"
    val made = "Generated in the field$stamp."
    val unread = if (!serverCopyUnread) "" else
        " This copy was prepared without a connection, so entries made elsewhere may not be included."
    return made + unread
}

fun reportWarnings(
    requestedTemplateId: String,
    template: ReportTemplate,
    settings: Map<String, JsonElement>?,
    format: String,
    questionnaires: DwQuestionnaireCopy = DwQuestionnaireCopy.UNKNOWN,
): List<String> {
    val said = ArrayList<String>()

    if (requestedTemplateId.isNotEmpty() && requestedTemplateId != template.id) {
        said += "Template '$requestedTemplateId' is not available, so the report was generated " +
            "with '${template.name}' instead."
    }

    val fonts = resolveFont(null, settings)
    if (fonts != null && format.equals("PDF", ignoreCase = true)) {
        said += "The PDF uses this phone's own typeface so that Odia, Devanagari and the rupee sign " +
            "print correctly. The Word document uses ${fonts.second}."
    }

    for (special in unsupportedSectionsIn(template, settings, questionnaires)) {
        val reason = UNSUPPORTED_SECTIONS[special] ?: continue
        if (said.none { it == reason }) said += reason
    }
    return said
}
