package com.designprototype.workshop.report

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * The stage-20 answers, resolved the way the server resolves them — the port of the report half of
 * `report_theme.py` (`resolve_accent`, `resolve_font`, `FONT_PRESETS`) and of
 * `design_workshops.report_meta` / `resolve_template_id`.
 *
 * ONE PRECEDENCE, EVERYWHERE. Every option in this file is read in the same order the whole report
 * pipeline reads everything: an explicit request wins if it said anything, the saved stage-20 answer
 * applies otherwise, and where neither spoke the template's own value stands untouched. That last
 * clause is what keeps this from restyling every report this app has ever generated the moment it
 * ships.
 *
 * AN UNKNOWN TOKEN RESOLVES TO NOTHING, never to a guess — an unknown font preset, an unknown accent
 * preset, an unknown template id. A phone or a record one release ahead of this build must not
 * silently produce a document in a typeface, a colour or a shape this build never chose.
 */

// --------------------------------------------------------------------------------------
// Reading a stage entry
// --------------------------------------------------------------------------------------

/**
 * One stage-20 value as the server's `str(raw).strip()` would read it, or "" for "nothing there".
 *
 * Only a primitive is text. An object or an array reaching a TEXT field is a corrupt record rather
 * than a title, and stringifying it would print `{"blocks":[…]}` onto the cover of a submitted
 * report — which is exactly what a naive `toString()` does to a rich-text value.
 */
internal fun settingText(settings: Map<String, JsonElement>?, key: String): String {
    val raw = settings?.get(key) ?: return ""
    if (raw !is JsonPrimitive || raw is JsonNull) return ""
    return raw.content.trim()
}

/** A stage-20 value as a colour string, matching Python's `isinstance(value, str)` gate. */
private fun settingHex(settings: Map<String, JsonElement>?, key: String): String? {
    val raw = settings?.get(key) ?: return null
    if (raw !is JsonPrimitive || raw is JsonNull || !raw.isString) return null
    return normaliseHex(raw.content)
}

// --------------------------------------------------------------------------------------
// Typefaces
// --------------------------------------------------------------------------------------
//
// WHAT A FONT CHOICE CAN AND CANNOT REACH ON A HANDSET, stated here because the honest answer is
// "one of the two files", and a picker that pretended otherwise would be worse than no picker.
//
//   .docx  HONOURS IT. [DocxWriter] writes the family name into `w:rFonts` and Word resolves it on
//          the machine that opens the document — which is a departmental desktop, not this phone.
//          This is the file that is submitted and the file an officer edits, so it is the one that
//          matters most, and it is why the setting is worth honouring at all on a device that has
//          none of these faces installed.
//   .pdf   DOES NOT, on this device even more absolutely than on the server. [PdfWriter] draws
//          every run with `Typeface.DEFAULT`, because the PDF has to carry Odia, Devanagari and the
//          rupee sign and the system face is the only one on an Android handset that can. There is
//          no Garamond on a field phone to substitute in. So the mismatch is REPORTED as a warning
//          rather than silently produced — see [reportWarnings].

/** key -> (label, heading family, body family). `report_theme.FONT_PRESETS`, in the same order. */
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

/** The answer meaning "leave the template's own typeface alone". */
const val DEFAULT_FONT: String = "DEFAULT"

/**
 * The (heading, body) families this report is set in, or null to leave the template's own.
 *
 * `uppercase(Locale.ROOT)` and not `uppercase()`: on a handset set to Turkish the default locale
 * maps "i" to "İ", so a designer whose phone is in Turkish would have "times" resolve to nothing
 * while the same record resolves on the server. The report must not depend on the device's language.
 */
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

/**
 * Which accent this report is written in, or null to leave the template's own palette alone.
 *
 * On the stage, `themeAccent` (the hex) is the authority and `themePreset` (the name) is read only
 * when it is missing. Both are stored because the name is what a designer recognises on their
 * return to the screen, but a preset whose hex this build does not know — a token from a newer
 * client — resolves to nothing rather than to a guess.
 */
fun resolveAccent(requested: String?, settings: Map<String, JsonElement>?): String? {
    normaliseHex(requested)?.let { return it }
    settingHex(settings, "themeAccent")?.let { return it }
    val preset = settings?.get("themePreset")
    if (preset is JsonPrimitive && preset !is JsonNull && preset.isString) {
        ACCENT_PRESETS.firstOrNull { it.key == preset.content }?.let { return it.hex }
    }
    return null
}

/**
 * Which template this report is built from: the request, then stage 20, then the workshop header.
 *
 * STAGE 20'S OWN PICKER WAS READ BY NOTHING, on the server and then again here. "Report template" is
 * required and BASIC, so the completeness gate demands it — and both surfaces resolved the template
 * from the header alone, skipping the answer entirely. A designer worked through 22 stages, reached
 * stage 20, chose "Photo catalogue" because the form insisted, generated the report and got the DCH
 * standard one, with nothing anywhere saying the field was inert.
 *
 * AN UNKNOWN ID IS IGNORED RATHER THAN OBEYED. [reportTemplate] falls back to the DCH standard for
 * anything it does not know, which would turn "this build has not heard of that template" into
 * "your chosen template is the default" with no way to tell the two apart.
 */
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

/** Whether this report should carry the transcript annexure — `workshop_transcripts.wants_transcripts`. */
fun wantsTranscripts(requested: Boolean?, settings: Map<String, JsonElement>?): Boolean {
    if (requested != null) return requested
    return askedSetting(settings, "includeTranscripts") == true
}

// --------------------------------------------------------------------------------------
// The cover and the running furniture
// --------------------------------------------------------------------------------------

/**
 * What the workshop header knows about itself.
 *
 * The server reads these from the database row; this device reads them from the draft's own title
 * and from stage 1, which is where the designer typed them. The field names are the server's so the
 * two can be read side by side — this is `design_workshops.report_meta`'s `record`.
 */
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

/**
 * `2026-02-10` -> `10 Feb 2026`. Unparseable input is printed verbatim — `report_builder._format_date`.
 *
 * THE NEGATIVE INDEX IS PART OF THE CONTRACT AND IS NOT A BUG BEING COPIED FOR FUN. Python's
 * `_MONTHS[month - 1]` with a month of 0 reads the LAST element, so the server prints "05 Dec 2026"
 * for "2026-00-05". A port that bounds-checked instead would print the string verbatim, and the
 * cover of the phone's copy and the office's copy would name different months for the same record.
 * The pinned case table carries that exact date.
 */
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

/**
 * "Submitted to X on 4 March 2031" — stage 20's two addressee fields, on the cover.
 *
 * Both were stored on this device and printed nowhere. A designer filled in "Submitted to" and a
 * submission date and the generated document named neither: the one page of the report whose job is
 * to say who it is for said nothing about who it is for. One line because they are one fact, and
 * each half stands alone when the other is blank.
 */
fun submissionLine(settings: Map<String, JsonElement>?): String {
    val to = settingText(settings, "submittedTo")
    val on = settingText(settings, "submissionDate")
    val whenText = if (on.isNotEmpty()) formatReportDate(on) else ""
    if (to.isNotEmpty() && whenText.isNotEmpty()) return "Submitted to $to on $whenText"
    if (to.isNotEmpty()) return "Submitted to $to"
    return if (whenText.isNotEmpty()) "Submitted on $whenText" else ""
}

/**
 * The cover page and the running furniture — `design_workshops.report_meta`.
 *
 * Every value stage 20 can carry is an OVERRIDE of something derived from the record, and each one
 * falls back to exactly what this produced before the overrides existed, so a workshop that never
 * opened stage 20 gets the same cover it always got.
 *
 * "Report title" was the worst of the ignored ones. A designer whose workshop is recorded as
 * "DPDW Barpali Jan-26" and who typed a proper title for the submitted document watched the report
 * print the internal shorthand, every time, with no indication that the field was inert.
 */
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

    // The page size is an enum on the wire and a free string in the stored entry, so an unknown
    // token falls back rather than throwing: a report that prints on the wrong paper is a nuisance,
    // and a report that refuses to print because a phone sent "A4 " is a lost afternoon.
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

// --------------------------------------------------------------------------------------
// What this device can and cannot do with the answers — the ledger
// --------------------------------------------------------------------------------------

/** How far a stage-20 answer reaches on THIS device. */
enum class SettingReach {
    /** Honoured in both files, the same way the server honours it. */
    APPLIED,

    /** Honoured in the .docx and impossible in the .pdf. The designer is told, per export. */
    DOCX_ONLY,

    /** Not honoured at all here. The designer is told whenever the answer would have mattered. */
    NOT_ON_DEVICE,
}

/** One stage-20 key and what actually becomes of it on this device. */
data class SettingSupport(val key: String, val reach: SettingReach, val note: String)

/**
 * EVERY key the registry declares for stage 20's `reportSettings`, with what this device does with
 * it. `ReportSettingsLedgerTest` asserts this list against the shipped registry in both directions.
 *
 * THE LIST IS THE POINT, not the documentation. A setting that is neither applied nor named here as
 * unsupported is exactly the bug this whole area exists to end: a switch the form saves, the sync
 * carries and the report ignores, which a designer discovers — if ever — by comparing their file
 * with the office's. The test fails the build when the registry gains a key nobody decided about,
 * so the decision cannot be skipped by forgetting.
 *
 * WHAT THIS LEDGER CANNOT SEE, recorded here because it was the largest on-device gap and it has no
 * row: the locator MAP and the front-page INFOGRAPHICS are not stage-20 switches at all. They are
 * template sections — DCH_STANDARD, the default, carries both — so no entry below could ever have
 * named them and a reader working down this list would have concluded the file was complete. They
 * are built now; the map's artisan pins are the one part that is not, for a reason that is about the
 * DATA on this handset and not the drawing (see `renderMap`).
 */
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
    SettingSupport("includeTranscripts", SettingReach.NOT_ON_DEVICE,
        "The transcript annexure is not built on this device, and the reason is the text and not " +
            "the drawing: design-workshop audio is transcribed SERVER-SIDE by the media queue onto " +
            "MediaFile.transcriptText, which no draft on this handset stores and no endpoint this " +
            "client binds ever asks for. The recordings are here; their transcripts are not. A " +
            "designer who asks for them is told so on every export."),
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

/**
 * The special sections this device cannot build, and why — one sentence each, said to the designer
 * rather than left as a silent hole in the file.
 *
 * A section absent from this map is one [buildWorkshopDocument] emits. Keeping the two together
 * means a section that becomes renderable is deleted from here in the same edit, and one that never
 * was cannot be quietly forgotten.
 */
val UNSUPPORTED_SECTIONS: Map<SpecialSection, String> = mapOf(
    SpecialSection.ANNEXURE_TRANSCRIPTS to
        "The recordings' transcripts are not appended on this device, although you asked for them. " +
            "The recordings are on this phone but their transcripts are not: workshop audio is " +
            "transcribed after it reaches the server. The office's copy of this report will carry " +
            "them.",
    // PHRASED CONDITIONALLY ("if a questionnaire is attached"), unlike every other entry here, and
    // that is forced by the data rather than by taste. The transcript entry can be unconditional
    // because `unsupportedSectionsIn` guards it with the designer's own `includeTranscripts` answer.
    // There is no equivalent switch for this one — attaching the questionnaire IS the request, and it
    // is a fact about a server row this device does not hold: `WorkshopRepository`'s "Custom
    // questionnaires" block falls back to the device for NOTHING, deliberately, because a cached form
    // cannot know a question was retired an hour ago. So a handset cannot tell an unattached workshop
    // from an attached one while offline, and an unconditional "the answers are missing from this
    // file" would be a false alarm on the majority of exports, which is how a designer learns to stop
    // reading warnings.
    SpecialSection.ANNEXURE_QUESTIONNAIRES to
        "If a questionnaire is attached to this workshop, the answers recorded against it are not " +
            "in this file. This device keeps no copy of them — the questionnaire screens read them " +
            "over the network — so a report generated here has nothing to print. The office's copy " +
            "of this report will carry them.",
)

/**
 * The same sections, as the NOUN PHRASE the file's own provenance line uses.
 *
 * Two wordings for one fact, and they are not redundant. [UNSUPPORTED_SECTIONS] is addressed to the
 * designer standing at the export screen, in the second person, about a thing they just did. This
 * one is addressed to whoever opens the document in an office next month and completes the sentence
 * "The office's copy of this report also carries …", so it has to be a thing rather than an
 * explanation. Both are keyed off the same enum, so a section that becomes renderable is deleted
 * from both in one edit and neither can be left asserting a gap that has closed.
 *
 * ANNEXURE_QUESTIONNAIRES IS DELIBERATELY ABSENT, and this is the one place the two maps are meant to
 * disagree — do not "fix" it by adding the noun phrase. [UNSUPPORTED_SECTIONS] is advisory, shown to
 * a designer holding the phone, who knows perfectly well whether they attached a questionnaire; it
 * can afford to say "if". THIS map's string goes INTO the document, addressed to an officer who
 * cannot check it, as the flat assertion "The office's copy of this report also carries …". Every
 * template carries the questionnaire annexure and there is no stage-20 toggle to guard it with, so
 * naming it here would print that sentence on every field copy of every workshop — including the
 * majority with no questionnaire attached at all, whose office copy carries nothing of the kind. A
 * report apologising for the absence of something that does not exist is exactly the defect the
 * transcript guard in [unsupportedSectionsIn] was added to prevent; here the guard cannot be written,
 * because the answers have no local copy for this device to count.
 *
 * The day the handset holds those answers, this becomes moot: the section is drawn, and it leaves
 * [UNSUPPORTED_SECTIONS] rather than joining this map.
 */
private val UNSUPPORTED_SECTION_NAMES: Map<SpecialSection, String> = mapOf(
    SpecialSection.ANNEXURE_TRANSCRIPTS to "the transcripts of the recordings",
)

/**
 * Which of [template]'s special sections this build does not draw, in the template's own order.
 *
 * ONE WALK, TWO READERS — [reportWarnings] and [fieldCopyNote]. They were written separately at
 * first and immediately disagreed about the transcript annexure, which every template carries and
 * which prints nothing at all unless the designer asked for it: the warning was correctly silent and
 * the note in the file was not, so a report nobody had asked transcripts for carried a line
 * apologising for their absence.
 *
 * Driven by the sections the RESOLVED template actually carries, not by the stage-20 toggles: those
 * switches only ever REMOVE a section, so a toggle turned on for a template that does not carry that
 * annexure changes nothing on the server either and must not be reported here.
 */
internal fun unsupportedSectionsIn(
    template: ReportTemplate,
    settings: Map<String, JsonElement>?,
): List<SpecialSection> {
    val found = ArrayList<SpecialSection>()
    for (section in template.sections) {
        val special = section.special ?: continue
        if (special !in UNSUPPORTED_SECTIONS) continue
        if (special == SpecialSection.ANNEXURE_TRANSCRIPTS && !wantsTranscripts(null, settings)) continue
        if (special !in found) found += special
    }
    return found
}

/**
 * The cover's provenance line: where and when this file was made, and what the office's copy has
 * that it does not.
 *
 * WHAT THIS ENDS. The officer handed the phone's PDF read it as THE report. Same cover, same running
 * foot, a self-consistent contents page — and nothing anywhere in it said which of the two copies of
 * that workshop was in their hands. `ReportScreen` exists partly so an office can match a delivered
 * file against its export log, and if the two copies are ever compared, a section present in one and
 * absent from the other with neither file admitting it looks like the designer altered the document.
 * Offline it is worse: the export-log call is best-effort, so the log the office would check is
 * empty.
 *
 * IT REPLACES the server's "Generated on <date>" in the same cover slot rather than being added
 * beside it, and carries the same date. One provenance line where the office's copy has one; the
 * whole of the divergence between the two lines is the fact that makes this one worth printing.
 *
 * IT IS NOT A WARNING AND MUST NOT READ AS ONE. `ReportPlan.warnings` are advisory, dismissible and
 * shown beside the file — they belong to the act of generating and are gone by the time the document
 * is read. This is a statement of fact about the file, addressed to its reader, and it shortens by
 * itself as the sections above land: when [unsupportedSectionsIn] is empty it says only where and
 * when the file was made.
 *
 * A COVER FOOTER LINE, not a heading, so it never enters the table of contents and never renumbers
 * anything.
 */
fun fieldCopyNote(
    template: ReportTemplate,
    settings: Map<String, JsonElement>?,
    generatedOn: String,
): String {
    val stamp = if (generatedOn.isBlank()) "" else " on $generatedOn"
    val made = "Generated on a handset in the field$stamp."
    val missing = unsupportedSectionsIn(template, settings).mapNotNull { UNSUPPORTED_SECTION_NAMES[it] }
    if (missing.isEmpty()) return made
    val listed = when (missing.size) {
        1 -> missing[0]
        else -> missing.dropLast(1).joinToString(", ") + " and " + missing.last()
    }
    return "$made The office's copy of this report also carries $listed."
}

/**
 * What to tell the designer about the file they are about to hand over.
 *
 * SILENCE IS THE FAILURE MODE. A setting that cannot be honoured must be named — the server says
 * the same things through `X-Report-Warnings` — because a difference the designer is told about is
 * a decision they can take (send the .docx instead, generate the office copy later) and the same
 * difference unmentioned is a defect they will be blamed for by whoever notices it first.
 *
 * Advisory only. Nothing here blocks an export; the file is written either way.
 */
fun reportWarnings(
    requestedTemplateId: String,
    template: ReportTemplate,
    settings: Map<String, JsonElement>?,
    format: String,
): List<String> {
    val said = ArrayList<String>()

    // The substitution warning `build_report` raises: a record naming a template this build has
    // retired still prints, and the designer is told which one it actually came out as.
    if (requestedTemplateId.isNotEmpty() && requestedTemplateId != template.id) {
        said += "Template '$requestedTemplateId' is not available in this version of the app; the " +
            "report was generated with '${template.name}' instead."
    }

    val fonts = resolveFont(null, settings)
    if (fonts != null && format.equals("PDF", ignoreCase = true)) {
        said += "The PDF is set in this device's own typeface, not ${fonts.second}. A PDF written " +
            "on a handset must draw Odia, Devanagari and the rupee sign, and the system face is " +
            "the only one here that can. The Word document does use ${fonts.second}."
    }

    // [unsupportedSectionsIn] carries the "which sections" judgement, including the reason the
    // transcript annexure is conditional: every template carries it and it prints nothing unless the
    // designer asked for it, so warning unconditionally would put a false alarm on every export.
    for (special in unsupportedSectionsIn(template, settings)) {
        val reason = UNSUPPORTED_SECTIONS[special] ?: continue
        if (said.none { it == reason }) said += reason
    }
    return said
}
