package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * WHICH BOXES ON MY DESIGNER PROFILE CARRY A MICROPHONE, AND THE FACT THAT NOTHING WAS FORGOTTEN.
 *
 * ── WHAT THIS DEFENDS ───────────────────────────────────────────────────────────────────────────
 *
 * The owner's instruction of 2026-08-28: *"On My Designer Profile, add the existing
 * microphone/dictation functionality to all applicable fields. Follow the same dictation behavior
 * already used throughout the other record pages. Exclude calendar fields and any other special
 * fields where dictation is not applicable. All remaining applicable fields should provide the mic
 * dictation button."*
 *
 * Two things in that sentence are testable and both of them are the kind that rot quietly.
 *
 * **"ALL APPLICABLE FIELDS"** is a claim about a set, and a set that lives only in a screen's layout
 * is a set nobody can check — a column added next year gets a microphone or does not get one
 * depending on which call site the author copied. So the classification is data
 * ([DESIGNER_PROFILE_DICTATED] and [DESIGNER_PROFILE_NOT_DICTATED]), every box asks it, and the
 * assertions below require the two tables to cover EVERY column of [ProfileForm], require every
 * exclusion to carry a written reason, and require every promised microphone to be wired to an
 * actual box. A column in neither table fails here rather than shipping as an omission that looks
 * like a decision.
 *
 * **"FOLLOW THE SAME DICTATION BEHAVIOR"** is a claim about two clients. `DesignerProfileForm.tsx`
 * answered the identical instruction on the web on the identical day, and the failure mode of one
 * requirement implemented twice is that the two lists agree on the day they are written and drift on
 * the next edit — which nobody notices, because nobody re-reads a `.tsx` while editing Kotlin. So
 * the web's list is read out of its own source and compared.
 *
 * ── WHAT THIS DELIBERATELY DOES NOT TEST ────────────────────────────────────────────────────────
 *
 * The recogniser. Not one line of speech plumbing lives in `DesignerProfileScreen.kt` — the screen
 * forwards to `RecordProseField`, which is the record forms' control and carries its own ladder,
 * its own refusals and its own tests. A copy of that machinery here would be a second copy to get
 * wrong, so the last assertion checks that the copy does not exist rather than that it works.
 */
class DesignerProfileScreenTest {

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The same helper `DwParentGroupParityTest` and `DesignerProfileRequiredFieldsTest` carry, for
     * the same reason: a Gradle test worker's working directory is not something to depend on, and a
     * test that SKIPPED when it could not find its subject would prove nothing on the day somebody
     * moves it. Missing is a failure, loudly. The `..`-prefixed candidates are what let it reach out
     * of `android/` and into `frontend/`, which is how the web's list is compared at all.
     */
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

    /** Every column the form edits, read off [ProfileForm] itself rather than listed again here. */
    private fun profileColumns(): Set<String> = ProfileForm::class.java.declaredFields
        // `$stable` is the Compose compiler's own static field on an `@Immutable` class, not a
        // column. Filtered by shape rather than by name so a future compiler's extra field is
        // filtered too — a synthetic field is never something a designer types into.
        .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) && !it.name.contains('$') }
        .map { it.name }
        .toSet()

    // ── The classification covers the form ───────────────────────────────────────────────────────

    @Test
    fun `every column on the profile is classified, and none of them twice`() {
        val columns = profileColumns()
        assertEquals(
            "ProfileForm no longer has twenty-one columns. That is fine — but the new one has to be " +
                "put in DESIGNER_PROFILE_DICTATED or in DESIGNER_PROFILE_NOT_DICTATED with a reason, " +
                "because a box that is neither is an omission nobody can tell from a decision",
            21,
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
        /*
          THE ONE ASSERTION THAT STOPS THE TABLE BECOMING A LIST OF NAMES.

          "This box has no microphone" is an observation anybody can make from the screen; the only
          thing worth writing down is WHY. An empty string, or the column name repeated, would
          satisfy the coverage test above while classifying nothing — so the reason has to be long
          enough to be an argument and punctuated like one.
        */
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
        // The owner excluded "calendar fields and any other special fields where dictation is not
        // applicable". These are this screen's reading of that clause, pinned so that a later sweep
        // that decides to light up "every remaining box" has to argue with a test rather than with
        // a comment.
        listOf(
            // A calendar, excluded by name in the instruction itself.
            "empanelmentDate",
            // Closed vocabularies: answered by picking from a served list, not by typing.
            "state",
            // Media slots: a camera, a gallery and a document picker. No text to speak.
            "photoMediaId", "signatureMediaId", "cvMediaId",
            // Numbers and identity: a recogniser returns words where these want digits, and a
            // mis-heard character in an empanelment number is indistinguishable from a right one.
            "experienceYears", "phone", "pincode", "empanelmentNo",
            // Addresses and URLs: punctuation a recogniser writes out as words.
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
        /*
          A CROSS-CHECK BETWEEN TWO RULES THAT WERE WRITTEN A DAY APART.

          `RequiredInput` on the record forms defaults `dictate` to true, on the argument that the
          mandatory boxes are the ones with the most typing friction. Two of this screen's four
          mandatory columns are prose and follow that rule; the other two are an address and a phone
          number and are the two boxes on the form where dictation is LEAST applicable. Both halves
          are deliberate, and a sweep that applied either half to all four would be wrong in one
          direction or the other.
        */
        assertTrue("Name as printed is prose and mandatory", "displayName" in DESIGNER_PROFILE_DICTATED)
        assertTrue("Qualification is prose and mandatory", "qualification" in DESIGNER_PROFILE_DICTATED)
        assertTrue("Phone is mandatory and is digits", "phone" in DESIGNER_PROFILE_NOT_DICTATED)
        assertTrue("Email is mandatory and is punctuation", "email" in DESIGNER_PROFILE_NOT_DICTATED)

        // And the four are still the four. `DesignerProfileRequiredFieldsTest` pins them against the
        // server's own list; this only checks that the dictation tables are talking about the same
        // columns, so the two rules cannot describe different forms.
        DESIGNER_PROFILE_REQUIRED_LABELS.keys.forEach { column ->
            assertTrue(
                "$column is mandatory but is in neither dictation table",
                column in DESIGNER_PROFILE_DICTATED || column in DESIGNER_PROFILE_NOT_DICTATED,
            )
        }
    }

    // ── The screen honours the classification ────────────────────────────────────────────────────

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

        /*
          AND NOTHING GOES ROUND THE TABLE. A hand-written flag at a call site is how the
          classification and the screen drift apart: the table would go on saying one thing while
          the box did another, and the comment explaining the exclusion would be describing a
          control that is right there on the phone. The one legitimate `dictate =` in this file is
          `ProfileText`'s own forwarding of its parameter, which names no literal.
        */
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
        /*
          THE PARITY THE INSTRUCTION ASKED FOR IN THE WORDS "follow the same dictation behavior".

          Read out of the web's own source rather than restated here, because a list copied into a
          test is a third place to forget. `DictatedField` is its single-line box and
          `DictatedTextArea` its multi-line one; both take the API column name as `name`, which is
          the same string this screen's tables are keyed by — the LABELS differ between the clients
          ("Name as printed" here, "Name" there) and are allowed to, the columns are not.
        */
        val web = repoFile(
            "frontend/components/designers/DesignerProfileForm.tsx",
            "../frontend/components/designers/DesignerProfileForm.tsx",
        ).readText(Charsets.UTF_8)

        val webDictated = Regex("<Dictated(?:Field|TextArea)\\s+name=\"([A-Za-z]+)\"")
            .findAll(web)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(
            "no <DictatedField> or <DictatedTextArea> was found in DesignerProfileForm.tsx — if the " +
                "web's dictation controls were renamed, this test has to follow them rather than be " +
                "deleted, because the parity it checks is the thing the owner asked for",
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

    // ── What the microphone must not have cost ───────────────────────────────────────────────────

    @Test
    fun `a line break never reaches a single-line column, typed or spoken`() {
        /*
          `RecordProseField` HAS NO `singleLine` PARAMETER — its boxes are paragraphs — so every box
          that moved onto it gained an IME with a newline key and a dictation control that can commit
          a phrase containing one. `DesignerProfile.addressLine` and its neighbours are typeset on a
          report cover; a stored newline is a broken line in a document already sent.
        */
        assertEquals("12 Nagar Jaipur", designerProfileOneLine("12 Nagar\nJaipur"))
        // The lone carriage return is the one that matters: it is invisible in a text box, it
        // survives the save, and a fold that only knew about `\n` would let it through.
        assertEquals("12 Nagar Jaipur", designerProfileOneLine("12 Nagar\rJaipur"))
        assertEquals("12 Nagar  Jaipur", designerProfileOneLine("12 Nagar\r\nJaipur"))
        // Replaced with a space and never dropped: "12 NagarJaipur" is a wrong address that still
        // looks like an address.
        assertFalse(designerProfileOneLine("12 Nagar\nJaipur").contains("NagarJaipur"))
        // And it leaves ordinary text exactly alone, including the scripts the local-name box is for.
        assertEquals("मीरा नायर", designerProfileOneLine("मीरा नायर"))
        assertEquals("", designerProfileOneLine(""))
    }

    @Test
    fun `the CV slot still says the report names the document rather than carrying it`() {
        /*
          A SENTENCE THAT HAS ALREADY BEEN WRONG TWICE ON THIS SCREEN, pinned while the file is being
          edited for an unrelated reason. It promised an annexure that no branch of this codebase
          produces, and then briefly promised the signature would reach the signature block, which
          `report_model.SignatureBlock` has no image slot for. It is the only warning a designer
          reads BEFORE they attach a CV and submit a report to a ministry believing it travelled
          inside.
        */
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
        /*
          THE ABSENCE THIS LANE IS MADE OF, CHECKED BY READING RATHER THAN ASSUMED.

          `RecordDictationButton` and `rememberRecordDictationAvailable` are private to
          `RecordProseField.kt`, which is what made this screen's boxes microphone-less in the first
          place. There were two ways out — use that component, or fork the recogniser here — and a
          fork would mean a second ladder, a second set of refusal sentences and a second set of
          bugs, with the refusal wording being the half that always drifts. The screen forwards
          instead, so none of these names may appear in it.

          Verified on 2026-08-28 with:
            rg -n "SpeechRecognizer|RecognizerIntent|DwAsrPcmRecorder|DwAsrSpeechModel|MediaRecorder"
               android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DesignerProfileScreen.kt
          which matched nothing. This test is that command, run on every build.
        */
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
