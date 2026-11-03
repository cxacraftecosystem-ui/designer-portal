package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignerProfileDto
import com.designprototype.workshop.report.Mark
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.ui.recordDocFromStored
import com.designprototype.workshop.ui.recordStoredKeepingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class DesignerProfileScreenTest {

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

    private fun screenSource(): String = repoFile(
        "src/main/java/com/designprototype/workshop/ui/designworkshop/DesignerProfileScreen.kt",
        "app/src/main/java/com/designprototype/workshop/ui/designworkshop/DesignerProfileScreen.kt",
        "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DesignerProfileScreen.kt",
    ).readText(Charsets.UTF_8)

    private fun profileColumns(): Set<String> = ProfileForm::class.java.declaredFields
        .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) && !it.name.contains('$') }
        .map { it.name }
        .toSet()

    @Test
    fun `every column on the profile is classified, and none of them twice`() {
        val columns = profileColumns()
        assertEquals(
            "ProfileForm no longer has twenty-four columns. That is fine — but the new one has to " +
                "be put in DESIGNER_PROFILE_DICTATED or in DESIGNER_PROFILE_NOT_DICTATED with a " +
                "reason, because a box that is neither is an omission nobody can tell from a " +
                "decision",
            24,
            columns.size,
        )

        val classified = DESIGNER_PROFILE_DICTATED + DESIGNER_PROFILE_NOT_DICTATED.keys
        assertEquals(
            "the two dictation tables and the form have drifted apart: " +
                "unclassified = ${columns - classified}, " +
                "classified but not a column = ${classified - columns}",
            columns,
            classified,
        )

        val both = DESIGNER_PROFILE_DICTATED.intersect(DESIGNER_PROFILE_NOT_DICTATED.keys)
        assertTrue(
            "$both is listed as dictated AND as not dictated — `dictates` would answer yes and the " +
                "written reason would say no, so the screen and its own explanation would disagree",
            both.isEmpty(),
        )
    }

    @Test
    fun `every box without a microphone says why, in a sentence`() {
        DESIGNER_PROFILE_NOT_DICTATED.forEach { (column, reason) ->
            assertTrue(
                "$column's exclusion carries no argument, only \"$reason\"",
                reason.trim().length >= 60,
            )
            assertTrue(
                "$column's reason is not a sentence: \"$reason\"",
                reason.trim().endsWith("."),
            )
            assertFalse(
                "$column's reason is the column name restated, which explains nothing",
                reason.trim().equals(column, ignoreCase = true),
            )
        }
    }

    @Test
    fun `the calendar, the closed vocabularies and the media slots are excluded by name`() {
        listOf(
            "empanelmentDate",
            "state",
            "photoMediaId", "signatureMediaId", "cvMediaId",
            "location",
            "experienceYears", "experienceMonths", "phone", "pincode", "empanelmentNo",
            "email", "website",
        ).forEach { column ->
            assertTrue(
                "$column must not carry a microphone — see DESIGNER_PROFILE_NOT_DICTATED for the " +
                    "reason it was excluded, and change that reason before changing this list",
                column !in DESIGNER_PROFILE_DICTATED,
            )
            assertTrue(
                "$column is excluded but no longer says why",
                DESIGNER_PROFILE_NOT_DICTATED.containsKey(column),
            )
        }
    }

    @Test
    fun `the two mandatory prose boxes dictate and the two mandatory identity boxes do not`() {
        assertTrue("Name as printed is prose and mandatory", "displayName" in DESIGNER_PROFILE_DICTATED)
        assertTrue("Qualification is prose and mandatory", "qualification" in DESIGNER_PROFILE_DICTATED)
        assertTrue("Phone is mandatory and is digits", "phone" in DESIGNER_PROFILE_NOT_DICTATED)
        assertTrue("Email is mandatory and is punctuation", "email" in DESIGNER_PROFILE_NOT_DICTATED)

        DESIGNER_PROFILE_REQUIRED_LABELS.keys.forEach { column ->
            assertTrue(
                "$column is mandatory but is in neither dictation table",
                column in DESIGNER_PROFILE_DICTATED || column in DESIGNER_PROFILE_NOT_DICTATED,
            )
        }
    }

    @Test
    fun `every promised microphone is wired to a box, and no box wires one round the table`() {
        val source = screenSource()
        val asked = Regex("dictates\\(\"([A-Za-z]+)\"\\)")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

        val promisedButUnwired = DESIGNER_PROFILE_DICTATED - asked
        assertTrue(
            "$promisedButUnwired are listed as dictated but no box on the screen asks `dictates` " +
                "for them — the table promises a microphone the designer never sees",
            promisedButUnwired.isEmpty(),
        )

        val unknown = asked - DESIGNER_PROFILE_DICTATED - DESIGNER_PROFILE_NOT_DICTATED.keys
        assertTrue(
            "$unknown reached `dictates` as a literal and is in neither table. `dictates` fails " +
                "closed, so that box silently has no microphone — which is exactly the failure this " +
                "test exists to turn into a red line",
            unknown.isEmpty(),
        )

        val handWritten = Regex("dictate\\s*=\\s*(?:true|false)\\b").findAll(source).count()
        assertEquals(
            "a box on DesignerProfileScreen.kt sets `dictate` to a literal instead of asking " +
                "`dictates(\"column\")`. Put the column in DESIGNER_PROFILE_DICTATED or in " +
                "DESIGNER_PROFILE_NOT_DICTATED with its reason, and ask the table",
            0,
            handWritten,
        )
    }

    @Test
    fun `the handset dictates exactly what the web dictates`() {
        val web = repoFile(
            "frontend/components/designers/DesignerProfileForm.tsx",
            "../frontend/components/designers/DesignerProfileForm.tsx",
        ).readText(Charsets.UTF_8)

        val webDictated = Regex("<(?:DictatedField|DictatedTextArea|RichTextField)\\s+name=\"([A-Za-z]+)\"")
            .findAll(web)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(
            "no <DictatedField>, <DictatedTextArea> or <RichTextField> was found in " +
                "DesignerProfileForm.tsx — if the web's dictation controls were renamed, this test " +
                "has to follow them rather than be deleted, because the parity it checks is the " +
                "thing the owner asked for",
            webDictated.isNotEmpty(),
        )
        assertEquals(
            "the two clients no longer offer dictation on the same columns: " +
                "only on the handset = ${DESIGNER_PROFILE_DICTATED - webDictated}, " +
                "only on the web = ${webDictated - DESIGNER_PROFILE_DICTATED}. One of the two was " +
                "edited alone, and a designer who fills this profile in on a phone and corrects it " +
                "in a browser meets two different forms",
            webDictated,
            DESIGNER_PROFILE_DICTATED,
        )
    }

    @Test
    fun `a line break never reaches a single-line column, typed or spoken`() {
        assertEquals("12 Nagar Jaipur", designerProfileOneLine("12 Nagar\nJaipur"))
        assertEquals("12 Nagar Jaipur", designerProfileOneLine("12 Nagar\rJaipur"))
        assertEquals("12 Nagar  Jaipur", designerProfileOneLine("12 Nagar\r\nJaipur"))
        assertFalse(designerProfileOneLine("12 Nagar\nJaipur").contains("NagarJaipur"))
        assertEquals("मीरा नायर", designerProfileOneLine("मीरा नायर"))
        assertEquals("", designerProfileOneLine(""))
    }

    @Test
    fun `an address written before rich text renders exactly as it always did`() {
        assertEquals("12 Nagar Road, Bagru", designerProfileAddressText("12 Nagar Road, Bagru"))
        assertEquals("मीरा नायर, जयपुर", designerProfileAddressText("मीरा नायर, जयपुर"))
        assertEquals("", designerProfileAddressText(null))
        assertEquals("", designerProfileAddressText(""))
        assertEquals("{Flat 2} Nagar Road", designerProfileAddressText("{Flat 2} Nagar Road"))
    }

    @Test
    fun `a formatted address shows its words and never its braces`() {
        val document = """{"blocks":[{"kind":"PARAGRAPH","spans":""" +
            """[{"text":"12 Nagar Road, "},{"text":"Bagru","marks":["BOLD"]}]}]}"""
        val shown = designerProfileAddressText(document)
        assertEquals("12 Nagar Road, Bagru", shown)
        assertFalse(
            "the address box is showing raw JSON — a designer reads that as a corrupted value and " +
                "the obvious repair is to delete it and retype, which destroys the web's document",
            shown.contains("blocks"),
        )
        val twoLines = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"12 Nagar Road"}]},""" +
            """{"kind":"PARAGRAPH","spans":[{"text":"Bagru 303007"}]}]}"""
        assertEquals("12 Nagar Road Bagru 303007", designerProfileAddressText(twoLines))
    }

    @Test
    fun `saving an untouched profile hands the formatted address back byte for byte`() {
        val document = """{"blocks":[{"kind":"PARAGRAPH","spans":""" +
            """[{"text":"12 Nagar Road, "},{"text":"Bagru","marks":["BOLD"]}]}]}"""
        val stored = DesignerProfileDto(displayName = "Meera Nair", addressLine = document)
        val form = stored.toForm()

        assertEquals(document, form.addressLine)
        assertEquals(document, form.toBody(form, document).addressLine)

        val elsewhere = form.copy(department = "Textile Design")
        assertEquals(document, elsewhere.toBody(form, document).addressLine)

        val respelt = """{ "blocks": [ { "spans": [ {"text":"12 Nagar Road, "}, """ +
            """{"marks":["BOLD"],"text":"Bagru"} ], "kind": "PARAGRAPH" } ] }"""
        assertEquals(document, form.copy(addressLine = respelt).toBody(form, document).addressLine)
    }

    @Test
    fun `formatting typed on the handset round-trips through the column`() {
        val typed = RichDoc(
            blocks = listOf(
                RichBlock(spans = listOf(RichSpan("12 Nagar Road, "), RichSpan("Bagru", setOf(Mark.BOLD)))),
                RichBlock(spans = listOf(RichSpan("Jaipur 303007", setOf(Mark.ITALIC)))),
            ),
        )
        val saved = recordStoredKeepingFormat(typed)!!
        assertTrue("a bolded address must be stored as a document: $saved", saved.startsWith("{\"blocks\""))
        assertEquals(typed, recordDocFromStored(saved))
        assertEquals("it must be stable on a second save", saved, recordStoredKeepingFormat(recordDocFromStored(saved)))

        val web = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"12 Nagar Road, "},""" +
            """{"text":"Bagru","marks":["BOLD"]}]},{"kind":"PARAGRAPH","spans":""" +
            """[{"text":"Jaipur 303007","marks":["ITALIC"]}]}]}"""
        assertEquals(web, saved)

        val form = DesignerProfileDto(addressLine = "12 Nagar Road").toForm()
        assertEquals(saved, form.copy(addressLine = saved).toBody(form, "12 Nagar Road").addressLine)

        val plain = RichDoc(blocks = listOf(RichBlock(spans = listOf(RichSpan("12 Nagar Road, Bagru")))))
        assertEquals("12 Nagar Road, Bagru", recordStoredKeepingFormat(plain))
        assertEquals(null, recordStoredKeepingFormat(RichDoc()))

        assertEquals("12 Nagar Road, Bagru Jaipur 303007", designerProfileAddressText(saved))
    }

    @Test
    fun `the address box is the rich editor that keeps its formatting`() {
        val source = screenSource()
        assertTrue(
            "the address must be edited in the rich box that stores documents (DA-28)",
            Regex("""label = "Address line",[\s\S]{0,400}rich = true,\s*keepFormatting = true""").containsMatchIn(source),
        )
        assertFalse(
            "the old 'saved as plain text' warning describes a loss that no longer happens",
            source.contains("It will be saved as plain text."),
        )
    }

    @Test
    fun `the CV slot still says the report names the document rather than carrying it`() {
        val source = screenSource()
        listOf(
            "The CV is not.",
            "a report file cannot carry a document",
            "so send the CV alongside the report.",
        ).forEach { clause ->
            assertTrue(
                "the CV slot no longer says \"$clause\" — if the wording changed, check that the " +
                    "screen still tells a designer the report does NOT carry the file, and update " +
                    "this test to the new sentence rather than dropping the check",
                source.contains(clause),
            )
        }
    }

    @Test
    fun `no dictation plumbing was copied into this screen`() {
        val source = screenSource()
        listOf(
            "SpeechRecognizer",
            "RecognizerIntent",
            "RecognitionListener",
            "DwAsrPcmRecorder",
            "DwAsrSpeechModel",
            "MediaRecorder",
            "android.speech",
            "Manifest.permission.RECORD_AUDIO",
        ).forEach { name ->
            assertFalse(
                "$name has appeared in DesignerProfileScreen.kt. A second copy of the dictation " +
                    "control is a second copy to get wrong; this screen is supposed to reach " +
                    "RecordProseField for all of it",
                source.contains(name),
            )
        }
        assertTrue(
            "DesignerProfileScreen.kt no longer imports RecordProseField, which is where its " +
                "microphone comes from",
            source.contains("import com.designprototype.workshop.ui.RecordProseField"),
        )
    }
}
