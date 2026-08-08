package com.designprototype.workshop.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin template catalogue, diffed against the SERVER'S OWN, by value.
 *
 * `src/test/resources/report_templates_pin.json` is not written by hand and must never be edited by
 * hand. It is dumped straight out of `backend/app/services/report_templates.py`,
 * `report_theme.py` and `design_workshops.py` inside the running API container:
 *
 *     docker cp scratchpad/gen_pin.py design-workshop-api:/tmp/gen_pin.py
 *     docker exec design-workshop-api sh -c 'cd /app && python /tmp/gen_pin.py /tmp/pin.json'
 *     docker cp design-workshop-api:/tmp/pin.json android/app/src/test/resources/report_templates_pin.json
 *
 * WHY BY VALUE AND NOT BY PROPERTY. A test that checked "the catalogue has six templates and each
 * has a cover section" passes against a port whose DCH sections are in the wrong order, whose photo
 * catalogue numbers its headings, or whose compact summary caps at ADVANCED — every one of which is
 * a different document from the office's for the same workshop, and none of which anybody would see
 * without opening the two files side by side. The pin carries 6 templates, 116 sections, 38
 * `apply_report_settings` cases and the accent, font, template-id, palette, cover-meta and date
 * arithmetic besides, and the whole of each is compared.
 *
 * WHAT TO DO WHEN THIS FAILS. Regenerate the pin from the server first. If the server has changed,
 * the port follows it; if it has not, the port is wrong. The one thing that must not happen is
 * editing the JSON to match the Kotlin.
 */
class ReportTemplatePinTest {

    private val pin: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/report_templates_pin.json")
        assertNotNull(
            "report_templates_pin.json is missing from the test resources; regenerate it from the " +
                "server (see this class's KDoc) rather than deleting the test.",
            stream,
        )
        Json.parseToJsonElement(stream!!.bufferedReader().use { it.readText() }).jsonObject
    }

    // ── reading the pin ──────────────────────────────────────────────────────────────────────────

    private fun JsonElement.stringOrNull(): String? =
        if (this is JsonNull) null else jsonPrimitive.contentOrNull

    private fun JsonElement?.settings(): Map<String, JsonElement>? =
        if (this == null || this is JsonNull) null else jsonObject.toMap()

    // ── the catalogue ────────────────────────────────────────────────────────────────────────────

    private fun assertTheme(where: String, expected: JsonObject, actual: ReportTheme) {
        assertEquals("$where accent", expected["accent"]!!.jsonPrimitive.content, actual.accent)
        assertEquals("$where accent_soft", expected["accent_soft"]!!.jsonPrimitive.content, actual.accentSoft)
        assertEquals("$where ink", expected["ink"]!!.jsonPrimitive.content, actual.ink)
        assertEquals("$where muted", expected["muted"]!!.jsonPrimitive.content, actual.muted)
        assertEquals("$where rule", expected["rule"]!!.jsonPrimitive.content, actual.rule)
        assertEquals(
            "$where table_header_fill",
            expected["table_header_fill"]!!.jsonPrimitive.content, actual.tableHeaderFill,
        )
        assertEquals(
            "$where table_header_text",
            expected["table_header_text"]!!.jsonPrimitive.content, actual.tableHeaderText,
        )
        assertEquals("$where zebra_fill", expected["zebra_fill"]!!.jsonPrimitive.content, actual.zebraFill)
        assertEquals("$where heading_font", expected["heading_font"]!!.jsonPrimitive.content, actual.headingFont)
        assertEquals("$where body_font", expected["body_font"]!!.jsonPrimitive.content, actual.bodyFont)
        assertEquals("$where complex_font", expected["complex_font"]!!.jsonPrimitive.content, actual.complexFont)
        assertEquals(
            "$where base_size_pt",
            expected["base_size_pt"]!!.jsonPrimitive.float, actual.baseSizePt, 0.0001f,
        )
    }

    private fun assertSection(where: String, expected: JsonObject, actual: TemplateSection) {
        assertEquals("$where stage_key", expected["stage_key"]!!.jsonPrimitive.content, actual.stageKey)
        assertEquals(
            "$where special",
            expected["special"]!!.jsonPrimitive.content, actual.special?.name.orEmpty(),
        )
        assertEquals("$where heading", expected["heading"]!!.jsonPrimitive.content, actual.heading)
        assertEquals(
            "$where presentation",
            expected["presentation"]!!.jsonPrimitive.content, actual.presentation.name,
        )
        assertEquals(
            "$where include_photos",
            expected["include_photos"]!!.jsonPrimitive.boolean, actual.includePhotos,
        )
        assertEquals("$where photo_columns", expected["photo_columns"]!!.jsonPrimitive.int, actual.photoColumns)
        assertEquals("$where max_photos", expected["max_photos"]!!.jsonPrimitive.int, actual.maxPhotos)
        assertEquals(
            "$where page_break_before",
            expected["page_break_before"]!!.jsonPrimitive.boolean, actual.pageBreakBefore,
        )
        assertEquals(
            "$where omit_if_empty",
            expected["omit_if_empty"]!!.jsonPrimitive.boolean, actual.omitIfEmpty,
        )
        assertEquals("$where intro", expected["intro"]!!.jsonPrimitive.content, actual.intro)
        assertEquals(
            "$where entities",
            expected["entities"]!!.jsonArray.map { it.jsonPrimitive.content }, actual.entities,
        )
        assertEquals(
            "$where include_figures",
            expected["include_figures"]!!.jsonPrimitive.boolean, actual.includeFigures,
        )
        assertEquals(
            "$where figures",
            expected["figures"]!!.jsonArray.map { it.jsonPrimitive.content }, actual.figures,
        )
    }

    private fun assertTemplate(where: String, expected: JsonObject, actual: ReportTemplate) {
        assertEquals("$where id", expected["id"]!!.jsonPrimitive.content, actual.id)
        assertEquals("$where name", expected["name"]!!.jsonPrimitive.content, actual.name)
        assertEquals("$where description", expected["description"]!!.jsonPrimitive.content, actual.description)
        assertEquals("$where max_tier", expected["max_tier"]!!.jsonPrimitive.content, actual.maxTier.name)
        assertEquals("$where page_size", expected["page_size"]!!.jsonPrimitive.content, actual.pageSize.name)
        assertEquals(
            "$where number_headings",
            expected["number_headings"]!!.jsonPrimitive.boolean, actual.numberHeadings,
        )
        assertEquals("$where organisation", expected["organisation"]!!.jsonPrimitive.content, actual.organisation)
        assertEquals(
            "$where show_empty_note",
            expected["show_empty_note"]!!.jsonPrimitive.boolean, actual.showEmptyNote,
        )
        assertTheme("$where theme", expected["theme"]!!.jsonObject, actual.theme)

        val sections = expected["sections"]!!.jsonArray
        assertEquals(
            "$where section COUNT — the phone's report would have a different number of sections " +
                "from the office's for the same workshop",
            sections.size, actual.sections.size,
        )
        sections.forEachIndexed { index, section ->
            assertSection("$where section $index", section.jsonObject, actual.sections[index])
        }
    }

    @Test
    fun `the six templates match the server field for field`() {
        val expected = pin["templates"]!!.jsonArray
        assertEquals("the number of templates", expected.size, REPORT_TEMPLATES.size)
        expected.forEachIndexed { index, template ->
            val id = template.jsonObject["id"]!!.jsonPrimitive.content
            assertTemplate("template $id", template.jsonObject, REPORT_TEMPLATES[index])
        }
    }

    @Test
    fun `the narrative order and the non-printing stages match the server`() {
        assertEquals(
            pin["narrative_order"]!!.jsonArray.map { it.jsonPrimitive.content },
            NARRATIVE_ORDER,
        )
        assertEquals(
            pin["non_printing_stages"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            NON_PRINTING_STAGES,
        )
    }

    @Test
    fun `the picker offers what the templates endpoint offers`() {
        val expected = pin["template_choices"]!!.jsonArray.map { it.jsonObject }
        val actual = templateChoices()
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, choice ->
            assertEquals(choice["id"]!!.jsonPrimitive.content, actual[index].id)
            assertEquals(choice["name"]!!.jsonPrimitive.content, actual[index].name)
            assertEquals(choice["description"]!!.jsonPrimitive.content, actual[index].description)
        }
    }

    @Test
    fun `an unknown template id falls back exactly as the server's does`() {
        pin["lookup"]!!.jsonArray.forEach { entry ->
            val asked = entry.jsonObject["asked"]!!.jsonPrimitive.content
            assertEquals(
                "reportTemplate(\"$asked\")",
                entry.jsonObject["id"]!!.jsonPrimitive.content,
                reportTemplate(asked).id,
            )
        }
    }

    // ── the settings arithmetic ──────────────────────────────────────────────────────────────────

    @Test
    fun `applyReportSettings agrees with the server over the whole case table`() {
        val cases = pin["apply_report_settings"]!!.jsonArray
        assertTrue("the pin should carry a substantial case table", cases.size >= 30)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val templateId = case["template_id"]!!.jsonPrimitive.content
            val settings = case["settings"].settings()
            val photos = case["include_photographs"]!!.let {
                if (it is JsonNull) null else it.jsonPrimitive.boolean
            }
            val actual = applyReportSettings(reportTemplate(templateId), settings, photos)
            assertTemplate("case '$name'", case["result"]!!.jsonObject, actual)
        }
    }

    @Test
    fun `resolveAccent agrees with the server`() {
        pin["resolve_accent"]!!.jsonArray.forEach { raw ->
            val case = raw.jsonObject
            val requested = case["requested"]!!.stringOrNull()
            val settings = case["settings"].settings()
            assertEquals(
                "resolveAccent($requested, ${case["settings"]})",
                case["result"]!!.stringOrNull(),
                resolveAccent(requested, settings),
            )
        }
    }

    @Test
    fun `resolveFont agrees with the server`() {
        pin["resolve_font"]!!.jsonArray.forEach { raw ->
            val case = raw.jsonObject
            val requested = case["requested"]!!.stringOrNull()
            val settings = case["settings"].settings()
            val expected = case["result"]!!.jsonArray.map { it.jsonPrimitive.content }
            val actual = resolveFont(requested, settings)?.let { listOf(it.first, it.second) }.orEmpty()
            assertEquals("resolveFont($requested, ${case["settings"]})", expected, actual)
        }
    }

    @Test
    fun `resolveTemplateId agrees with the server`() {
        pin["resolve_template_id"]!!.jsonArray.forEach { raw ->
            val case = raw.jsonObject
            val requested = case["requested"]!!.stringOrNull()
            val settings = case["settings"].settings()
            val record = case["record_template_id"]!!.jsonPrimitive.content
            assertEquals(
                "resolveTemplateId($requested, ${case["settings"]}, $record)",
                case["result"]!!.jsonPrimitive.content,
                resolveTemplateId(requested, settings, record),
            )
        }
    }

    @Test
    fun `themeFromAccent over the template palettes agrees with the server`() {
        pin["theme_from_accent"]!!.jsonArray.forEach { raw ->
            val case = raw.jsonObject
            val accent = case["accent"]!!.stringOrNull()
            val base = reportTemplate(case["base"]!!.jsonPrimitive.content).theme
            assertTheme(
                "themeFromAccent($accent, base=${case["base"]})",
                case["result"]!!.jsonObject,
                themeFromAccent(accent, base),
            )
        }
    }

    // ── the cover and the running furniture ──────────────────────────────────────────────────────

    @Test
    fun `reportMetaFor agrees with the server's report_meta`() {
        pin["report_meta"]!!.jsonArray.forEach { raw ->
            val case = raw.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val recordJson = case["record"]!!.jsonObject
            fun text(key: String) = recordJson[key]!!.jsonPrimitive.content
            val record = ReportRecord(
                id = text("id"),
                title = text("title"),
                craftName = text("craftName"),
                clusterName = text("clusterName"),
                state = text("state"),
                designerName = text("designerName"),
                implementingAgency = text("implementingAgency"),
                workshopCode = text("workshopCode"),
            )
            val meta = reportMetaFor(
                record = record,
                templateId = case["template_id"]!!.jsonPrimitive.content,
                settings = case["settings"].settings(),
                // The server stamps the clock here and the pin drops the field; nothing else in
                // ReportMeta is derived from it, so the comparison is complete without it.
                generatedAt = "",
            )
            val expected = case["result"]!!.jsonObject
            assertEquals("$name title", expected["title"]!!.jsonPrimitive.content, meta.title)
            assertEquals("$name subtitle", expected["subtitle"]!!.jsonPrimitive.content, meta.subtitle)
            assertEquals("$name author", expected["author"]!!.jsonPrimitive.content, meta.author)
            assertEquals(
                "$name organisation",
                expected["organisation"]!!.jsonPrimitive.content, meta.organisation,
            )
            assertEquals(
                "$name template_id",
                expected["template_id"]!!.jsonPrimitive.content, meta.templateId,
            )
            assertEquals(
                "$name template_name",
                expected["template_name"]!!.jsonPrimitive.content, meta.templateName,
            )
            assertEquals(
                "$name workshop_id",
                expected["workshop_id"]!!.jsonPrimitive.content, meta.workshopId,
            )
            assertEquals("$name page_size", expected["page_size"]!!.jsonPrimitive.content, meta.pageSize.name)
            assertEquals(
                "$name header_text",
                expected["header_text"]!!.jsonPrimitive.content, meta.headerText,
            )
            assertEquals(
                "$name footer_text",
                expected["footer_text"]!!.jsonPrimitive.content, meta.footerText,
            )
        }
    }

    @Test
    fun `the submission line agrees with the server`() {
        pin["submission_line"]!!.jsonArray.forEach { raw ->
            val case = raw.jsonObject
            assertEquals(
                "submissionLine(${case["settings"]})",
                case["result"]!!.jsonPrimitive.content,
                submissionLine(case["settings"].settings()),
            )
        }
    }

    @Test
    fun `the cover date agrees with the server, including the month it reads backwards`() {
        pin["format_date"]!!.jsonArray.forEach { raw ->
            val case = raw.jsonObject
            val iso = case["iso"]!!.jsonPrimitive.content
            assertEquals(
                "formatReportDate(\"$iso\")",
                case["result"]!!.jsonPrimitive.content,
                formatReportDate(iso),
            )
        }
    }

    // ── the trap that is invisible from the Kotlin side ──────────────────────────────────────────

    @Test
    fun `a boolean stored as the string false keeps its section, as it does on the server`() {
        // Called out on its own because it is the one place a reasonable-looking port silently
        // disagrees. `bool("false")` is TRUE in Python, so the server keeps the table of contents;
        // kotlinx's `booleanOrNull` answers false for the same value and would take it away. One
        // record, two files, one with a contents page and one without.
        val kept = applyReportSettings(
            reportTemplate("DCH_STANDARD"),
            mapOf("includeTableOfContents" to JsonPrimitive("false")),
        )
        assertTrue(
            "a stage-20 boolean stored as the STRING \"false\" must not remove the section",
            kept.sections.any { it.special == SpecialSection.TOC },
        )

        val removed = applyReportSettings(
            reportTemplate("DCH_STANDARD"),
            mapOf("includeTableOfContents" to JsonPrimitive(false)),
        )
        assertTrue(
            "a real false must remove it",
            removed.sections.none { it.special == SpecialSection.TOC },
        )
    }

    @Test
    fun `nothing saved returns the template untouched, by identity`() {
        // The "silent where nobody asked" guarantee, which is what stops this feature restyling
        // every report the app has ever generated the moment it ships.
        val template = reportTemplate("DETAILED_TECHNICAL")
        assertTrue(template === applyReportSettings(template, null))
        assertTrue(template === applyReportSettings(template, emptyMap()))
    }

    @Test
    fun `the pin carries every section the tests read, and the font list`() {
        listOf(
            "narrative_order", "non_printing_stages", "templates", "template_choices", "font_presets",
            "lookup", "apply_report_settings", "resolve_accent", "resolve_font", "resolve_template_id",
            "theme_from_accent", "report_meta", "submission_line", "format_date",
        ).forEach { key ->
            assertNotNull("the pin has no '$key'", pin[key])
        }
        val fonts = pin["font_presets"]!!.jsonObject
        assertEquals("the font preset list", fonts.keys, FONT_PRESETS.keys)
        fonts.forEach { (key, value) ->
            val expected = (value as JsonArray).map { it.jsonPrimitive.content }
            val actual = FONT_PRESETS.getValue(key).let { listOf(it.first, it.second, it.third) }
            assertEquals("font preset $key", expected, actual)
        }
    }
}
