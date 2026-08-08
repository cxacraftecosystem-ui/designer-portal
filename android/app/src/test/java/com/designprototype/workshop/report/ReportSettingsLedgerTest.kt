package com.designprototype.workshop.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every stage-20 answer is either applied on this device or named as unsupported, with a reason.
 *
 * THIS TEST IS THE FEATURE. Seventeen switches on stage 20 were saved by the form, carried by the
 * sync and read by nothing on the handset — and the reason that could happen, and could stay
 * happening, is that nothing anywhere had to have an opinion about them. A registry key that no
 * code mentions is invisible: the form renders it from the registry, the store keeps it because it
 * keeps everything, and the report simply never asks. The designer finds out by comparing their
 * file with the office's, if ever.
 *
 * So the ledger is asserted in BOTH directions against the shipped registry. A key the registry
 * gains and nobody decided about fails the build here, at the moment it is added, rather than in a
 * district office a year later.
 *
 * It reads the BUNDLED ASSET rather than a copy, because the bundled asset is what the app actually
 * renders stage 20 from on first launch with no network.
 */
class ReportSettingsLedgerTest {

    /**
     * The registry as shipped in the APK.
     *
     * Located by walking up from the test's working directory rather than by assuming one: Gradle
     * runs unit tests with the module directory as the working directory, but that is a default
     * rather than a contract, and a test that silently cannot find the registry would pass by
     * checking nothing at all.
     */
    private val assetKeys: List<String> by lazy {
        val relative = "app/src/main/assets/design-workshop-schema.json"
        var directory: File? = File("").absoluteFile
        var found: File? = null
        var hops = 0
        while (directory != null && hops < 8 && found == null) {
            found = listOf(
                File(directory, "src/main/assets/design-workshop-schema.json"),
                File(directory, relative),
                File(directory, "android/$relative"),
            ).firstOrNull { it.isFile }
            directory = directory.parentFile
            hops += 1
        }
        val file = requireNotNull(found) {
            "could not find design-workshop-schema.json from ${File("").absolutePath}"
        }
        val registry = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        registry["stages"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["key"]!!.jsonPrimitive.content == "REPORT_GENERATION" }["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["key"]!!.jsonPrimitive.content == "reportSettings" }["fields"]!!.jsonArray
            .map { it.jsonObject["key"]!!.jsonPrimitive.content }
    }

    @Test
    fun `the registry declares the keys the ledger expects, and no others`() {
        assertTrue(
            "the registry's reportSettings entity looks wrong: $assetKeys",
            assetKeys.size >= 20,
        )
        assertEquals(
            "a stage-20 setting the registry declares is missing from STAGE_20_SETTINGS. It is " +
                "therefore a switch this app saves and ignores in silence — decide what happens to " +
                "it and record the decision in the ledger.",
            emptyList<String>(),
            assetKeys - STAGE_20_SETTINGS.map { it.key }.toSet(),
        )
        assertEquals(
            "STAGE_20_SETTINGS names a key the registry does not declare; the ledger is describing " +
                "a setting that no longer exists.",
            emptyList<String>(),
            STAGE_20_SETTINGS.map { it.key } - assetKeys.toSet(),
        )
    }

    @Test
    fun `no key is listed twice`() {
        val keys = STAGE_20_SETTINGS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `every entry says something, and every unhonoured one says why`() {
        STAGE_20_SETTINGS.forEach { entry ->
            assertTrue(
                "${entry.key} has no note; an entry that does not say what becomes of the answer is " +
                    "the same silence the ledger exists to end",
                entry.note.length >= 20,
            )
        }
        // An UNSUPPORTED entry that did not explain itself would be worse than no entry: a designer
        // reading "not supported" with no reason cannot tell whether to send the file or wait.
        STAGE_20_SETTINGS
            .filter { it.reach != SettingReach.APPLIED }
            .forEach { assertTrue("${it.key} must say why", it.note.length >= 40) }
    }

    @Test
    fun `the transcript annexure is the only toggle this device cannot honour`() {
        // Pinned as a LIST rather than a count so that honouring one of them is a deliberate edit to
        // this line and not a number that quietly drifts. The photographic and completeness
        // annexures are now built here; the transcript one is not, and the reason is DATA rather
        // than drawing — workshop audio is transcribed server-side and its text never reaches this
        // handset. Closing that gap means giving the device the transcripts, not the renderer.
        assertEquals(
            listOf("includeTranscripts"),
            STAGE_20_SETTINGS.filter { it.reach == SettingReach.NOT_ON_DEVICE }.map { it.key },
        )
        assertEquals(
            listOf("fontPreset"),
            STAGE_20_SETTINGS.filter { it.reach == SettingReach.DOCX_ONLY }.map { it.key },
        )
    }

    @Test
    fun `a typeface is reported against the PDF and not against the Word document`() {
        val settings = mapOf("fontPreset" to kotlinx.serialization.json.JsonPrimitive("GARAMOND"))
        val template = reportTemplate("DIC_STANDARD")

        val pdf = reportWarnings("DIC_STANDARD", template, settings, "PDF")
        assertTrue(
            "a PDF drawn on a handset cannot be set in Garamond and the designer must be told: $pdf",
            pdf.any { it.contains("Garamond") },
        )
        val docx = reportWarnings("DIC_STANDARD", template, settings, "DOCX")
        assertTrue(
            "the .docx DOES carry the typeface, so warning about it there is noise: $docx",
            docx.none { it.contains("Garamond") },
        )
    }

    @Test
    fun `a template this build has retired is reported rather than silently substituted`() {
        val substituted = reportTemplate("DCH_2019_LEGACY")
        val said = reportWarnings("DCH_2019_LEGACY", substituted, null, "DOCX")
        assertTrue(
            "a retired template id must be named, or the designer cannot tell why the document " +
                "looks different: $said",
            said.any { it.contains("DCH_2019_LEGACY") },
        )
        // And an id this build DOES know produces no substitution warning. The map and the
        // infographics used to warn here as well; both are drawn on this device now, so the only
        // section left that can warn is the transcript annexure, and only when it was asked for.
        assertTrue(
            reportWarnings("DCH_STANDARD", reportTemplate("DCH_STANDARD"), null, "DOCX")
                .none { it.contains("not available in this version") }
        )
    }

    @Test
    fun `the map and the infographics are no longer disowned`() {
        // Both are drawn on this device now. A file that carries the front-page figures AND a note
        // saying it does not is worse than either alone — the same claim the annexures had to make
        // when they landed.
        val said = reportWarnings("DCH_STANDARD", reportTemplate("DCH_STANDARD"), null, "DOCX")
        assertEquals(
            "the warnings still disown a section this build draws: $said",
            emptyList<String>(),
            said.filter { it.contains("map") || it.contains("infographics") },
        )
    }

    @Test
    fun `the file's own provenance line names only what is genuinely missing`() {
        val template = reportTemplate("DCH_STANDARD")
        val asked = fieldCopyNote(
            template,
            mapOf("includeTranscripts" to kotlinx.serialization.json.JsonPrimitive(true)),
            "04 March 2026",
        )
        assertTrue("the line must date the file: $asked", asked.contains("04 March 2026"))
        assertTrue(
            "a designer who asked for transcripts gets a file that says the office's copy has " +
                "them: $asked",
            asked.contains("also carries the transcripts of the recordings"),
        )
        // Unasked, the transcript annexure prints nothing on either surface, so there is no
        // difference between the two copies to declare.
        val unasked = fieldCopyNote(template, emptyMap(), "04 March 2026")
        assertTrue("nothing is outstanding here: $unasked", !unasked.contains("also carries"))
        assertTrue("and it still says which copy this is: $unasked", unasked.contains("handset"))
        // With no timestamp it still names the surface rather than printing a dangling "on ".
        assertEquals("Generated on a handset in the field.", fieldCopyNote(template, emptyMap(), ""))
    }

    @Test
    fun `the transcript annexure is only reported when the designer asked for it`() {
        val template = reportTemplate("DCH_STANDARD")
        val unasked = reportWarnings("DCH_STANDARD", template, emptyMap(), "DOCX")
        assertTrue(
            "every template carries the transcript annexure and it prints nothing unless asked, " +
                "so warning unconditionally would put a false alarm on every export: $unasked",
            unasked.none { it.contains("transcripts") },
        )
        val asked = reportWarnings(
            "DCH_STANDARD",
            template,
            mapOf("includeTranscripts" to kotlinx.serialization.json.JsonPrimitive(true)),
            "DOCX",
        )
        assertTrue(
            "a designer who ticked 'append the transcripts' and gets none must be told: $asked",
            asked.any { it.contains("transcripts") },
        )
    }
}
