package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DW-14 (UX audit 2026-10-16), owner ruling 2026-10-17, on the handset.
 *
 *  1. Every field's `help` is one short line; the rest is `details`, opened by holding the field
 *     (owner, 2026-10-21 — the "i" row under every box is gone).
 *  2. The screen reader reaches the details through a "Show help" action on the caption.
 *  3. Dictate is a 48dp microphone in the box's trailing icon, named "Dictate", with the language in
 *     a menu from the caret beside it (or a long press) — no "English (India)" text button.
 *
 * The registry half reads the SHIPPED asset — a dump of what the API serves — through the app's own
 * decoder, so it also proves `details` survives the trip into [FieldDto]. The web's
 * `e2e/stage-help-dictate-unit.spec.ts` and the backend's `test_stage_help_copy.py` hold the same rule.
 */
class DwStageHelpDictateTest {

    private val schema: SchemaResponse by lazy {
        Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString(SchemaResponse.serializer(), asset().readText())
    }

    private val fields: List<Pair<String, FieldDto>> by lazy {
        schema.stages.flatMap { stage ->
            stage.entities.flatMap { entity -> entity.fields.map { "${entity.key}.${it.key}" to it } }
        }
    }

    @Test
    fun `every help line the APK ships is one sentence of at most 120 characters`() {
        val helps = fields.filter { it.second.help.isNotBlank() }
        assertTrue("the walk found too few help lines to mean anything", helps.size > 100)
        val long = helps.filter { it.second.help.length > 120 }.map { it.first }
        assertEquals("move the rest of these into details", emptyList<String>(), long)
        val sentenceBreak = Regex("[.!?…][\"”’)]?\\s+\\S")
        val twoSentences = helps.filter { sentenceBreak.containsMatchIn(it.second.help.trim()) }.map { it.first }
        assertEquals("one sentence per help line", emptyList<String>(), twoSentences)
    }

    @Test
    fun `details decode into the field and are never blank where present`() {
        val withDetails = fields.filter { DwFieldDetails.has(it.second) }
        assertTrue("no field in the shipped asset carries details", withDetails.isNotEmpty())
        val aadhaar = fields.first { it.first == "participant.aadhaarNumber" }.second
        assertTrue(aadhaar.help.contains("last four digits"))
        assertTrue(aadhaar.details.contains("XXXX XXXX"))
        assertFalse(DwFieldDetails.has(FieldDto(key = "x", label = "X", details = "  ")))
    }

    @Test
    fun `the help card says what it is about and the screen reader action is Show help`() {
        assertEquals("About Aadhaar number", DwFieldDetails.label("Aadhaar number"))
        assertEquals("Show help", DwFieldDetails.ACTION_LABEL)
        assertEquals("Close help", DwFieldDetails.CLOSE_LABEL)
    }

    @Test
    fun `no info toggle row is drawn, the field is held for its details, and reference fields share the caption`() {
        val renderer = source("ui/designworkshop/FieldRenderer.kt")
        assertFalse("the \"i\" row is gone", renderer.contains("Icons.Outlined.Info"))
        assertFalse(renderer.contains("stateDescription = DwFieldDetails.state(open)"))
        assertTrue(renderer.contains("DwFieldHelpHost(field, modifier.fillMaxWidth())"))
        assertTrue(renderer.contains("DwHelpCaptionText(help, field, openHelp)"))
        val help = source("ui/designworkshop/DwFieldHelp.kt")
        assertTrue(help.contains("PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)"))
        assertTrue(help.contains("customActions = listOf(action)"))
        val reference = source("ui/designworkshop/DwReferenceField.kt")
        assertEquals(2, Regex("FieldCaption\\(field\\)").findAll(reference).count())
        // The microphone keeps its own long press (the language menu).
        assertTrue(source("ui/designworkshop/DwDictation.kt").contains(".dwClaimsHold()"))
    }

    /**
     * Every field type that carries `details` in the shipped asset must reach [FieldCaption], or its
     * help is unreachable by screen reader on the handset while the web shows it. Read off the renderer's `when (type)`:
     * a branch either draws the caption itself, hands it to a widget as `helpRow`, or calls a helper
     * whose own body draws it.
     */
    @Test
    fun `every field type with details reaches FieldCaption`() {
        val renderer = source("ui/designworkshop/FieldRenderer.kt").replace("\r\n", "\n")
        val start = renderer.indexOf("            DwFieldType.RICH_TEXT -> {")
        assertTrue("the renderer's type switch moved", start > 0)
        val end = renderer.indexOf("\n        }\n", start)
        val switch = renderer.substring(start, end)
        val heads = Regex("(?m)^ {12}((?:DwFieldType\\.[A-Z_]+,?\\s*)+)->").findAll(switch).toList()
        val branches = mutableMapOf<String, String>()
        heads.forEachIndexed { i, head ->
            val body = switch.substring(head.range.first, heads.getOrNull(i + 1)?.range?.first ?: switch.length)
            Regex("DwFieldType\\.([A-Z_]+)").findAll(head.groupValues[1]).forEach { branches[it.groupValues[1]] = body }
        }
        val sources = source("ui/designworkshop/FieldRenderer.kt") + source("ui/designworkshop/DwReferenceField.kt")
        fun helperDrawsCaption(name: String): Boolean {
            val at = sources.indexOf("fun $name(")
            if (at < 0) return false
            val next = Regex("\\n(?:private |internal )?fun ").find(sources, at + 1)?.range?.first ?: sources.length
            return sources.substring(at, next).contains("FieldCaption(field")
        }
        val helpers = listOf(
            "ScalarInput", "DateField", "BoolField", "TagsField", "GeoField", "MediaField",
            "DwNumberedPointsInput", "DwWorkshopNameField", "DwReferenceSelectField",
        )
        helpers.forEach { assertTrue("$it no longer draws FieldCaption", helperDrawsCaption(it)) }
        fun reaches(body: String) = body.contains("FieldCaption(field") || helpers.any { body.contains("$it(") }
        val withDetails = fields.map { it.second }.filter { DwFieldDetails.has(it) }.map { it.type }.toSet()
        assertTrue(withDetails.containsAll(listOf("RICH_TEXT", "GEO")))
        val missing = withDetails.filter { type -> branches[type]?.let(::reaches) != true }
        assertEquals("these types drop the help caption", emptyList<String>(), missing)
        // The two widgets that draw their own help take the caption in its place.
        assertTrue(source("ui/designworkshop/RichTextEditor.kt").contains("helpRow()"))
        assertTrue(source("ui/designworkshop/DwLocationField.kt").contains("helpRow()"))
        assertEquals(2, Regex("helpRow = \\{ FieldCaption\\(field\\) \\}").findAll(renderer).count())
    }

    @Test
    fun `the microphone is named Dictate, is 48dp, and fills while it records`() {
        assertEquals(48, DwDictationAdornment.TARGET_DP)
        assertEquals("Dictate", DwDictationAdornment.label(DwDictationState.IDLE))
        assertEquals("Stop dictating", DwDictationAdornment.label(DwDictationState.LISTENING))
        assertEquals("Stop and transcribe", DwDictationAdornment.label(DwDictationState.RECORDING))
        assertEquals("In English (India)", DwDictationAdornment.languageState("English (India)"))
        assertEquals("Dictation language: Hindi", DwDictationAdornment.languageButton("Hindi"))
        assertFalse(DwDictationAdornment.filled(DwDictationState.IDLE))
        DwDictationState.values().filter { it != DwDictationState.IDLE }.forEach {
            assertTrue("$it must be visible without motion", DwDictationAdornment.filled(it))
        }
    }

    @Test
    fun `the language is a menu from the caret or a long press, and the text button is gone`() {
        val dictation = source("ui/designworkshop/DwDictation.kt")
        assertTrue(dictation.contains("DropdownMenu(expanded = showLanguages"))
        assertTrue(dictation.contains("onLongClickLabel = DwDictationAdornment.LONG_PRESS_LABEL"))
        assertTrue(dictation.contains("role = Role.RadioButton"))
        assertTrue(dictation.contains(".size(DwDictationAdornment.TARGET_DP.dp)"))
        assertFalse("the old text button that printed the language", dictation.contains("contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)"))
        assertFalse(dictation.contains("label = \"Dictation language\","))
        // And it is still drawn in the box's trailing icon on the ordinary text field.
        val renderer = source("ui/designworkshop/FieldRenderer.kt")
        assertTrue(renderer.contains("trailingIcon = if (!canDictate) null else {"))
    }

    @Test
    fun `the workshop-name dropdown carries the microphone beside its trigger, not under the box`() {
        val renderer = source("ui/designworkshop/FieldRenderer.kt")
        val at = renderer.indexOf("private fun DwWorkshopNameField(")
        assertTrue("DwWorkshopNameField moved", at > 0)
        val body = renderer.substring(at, Regex("\\n(?:private |internal )?fun ").find(renderer, at + 1)!!.range.first)
        val row = body.indexOf("Row(verticalAlignment = Alignment.Bottom) {")
        val select = body.indexOf("SearchableSelectField(", row)
        val mic = body.indexOf("DwDictationButton(", select)
        val offerLine = body.indexOf("dwWorkshopNameOfferLine(")
        assertTrue("the select and the mic share one row", row in 0 until select)
        assertTrue(body.substring(select, select + 120).contains("modifier = Modifier.weight(1f)"))
        assertTrue("the mic is drawn before the lines under the box", mic in (select + 1) until offerLine)
        assertEquals(1, Regex("DwDictationButton\\(").findAll(body).count())
    }

    private fun repoFile(vararg relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }

    private fun asset(): File = repoFile(
        "src/main/assets/design-workshop-schema.json",
        "app/src/main/assets/design-workshop-schema.json",
        "android/app/src/main/assets/design-workshop-schema.json",
    )

    private fun source(name: String): String = repoFile(
        "src/main/java/com/designprototype/workshop/$name",
        "app/src/main/java/com/designprototype/workshop/$name",
        "android/app/src/main/java/com/designprototype/workshop/$name",
    ).readText()
}
