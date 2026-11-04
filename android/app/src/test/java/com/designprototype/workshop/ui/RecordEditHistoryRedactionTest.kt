package com.designprototype.workshop.ui

import com.designprototype.workshop.data.RecordRevisionDto
import com.designprototype.workshop.data.RevisionChange
import com.designprototype.workshop.data.UserDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale

class RecordEditHistoryRedactionTest {

    private fun str(value: String?) = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun row(field: String, old: String?, new: String?) =
        recordRevisionChangeRow(field, RevisionChange(old = str(old), new = str(new)))

    @Test
    fun everyServerPlaceholderPairIsRecognisedOnEveryRedactedColumn() {
        for (field in RecordRevisionRedaction.FIELDS) {
            for ((old, new) in RecordRevisionRedaction.PLACEHOLDER_PAIRS) {
                val r = row(field, old, new)
                assertTrue(
                    "$field: \"$old\" -> \"$new\" is one of access._redacted_change's four " +
                        "transitions and must be classified as a placeholder, not as a value",
                    r.redacted
                )
                assertEquals(old, r.old)
                assertEquals(new, r.new)
            }
        }
    }

    @Test
    fun noPlaceholderPairSaysTheSameThingOnBothSides() {
        for ((old, new) in RecordRevisionRedaction.PLACEHOLDER_PAIRS) {
            assertFalse("a placeholder pair that reads the same twice hides the edit", old == new)
        }
        assertEquals(
            "the closed set is four transitions; a fifth means access.py grew one and this copy did not",
            4,
            RecordRevisionRedaction.PLACEHOLDER_PAIRS.size
        )
    }

    @Test
    fun halfOfAPlaceholderPairIsNotAPlaceholder() {
        val r = row("phone", RecordRevisionRedaction.HAD_VALUE, "9876543210")
        assertFalse(r.redacted)
        assertEquals("9876543210", r.new)
    }

    @Test
    fun aReversedPairIsNotAPlaceholder() {
        val r = row("email", RecordRevisionRedaction.CLEARED, RecordRevisionRedaction.HAD_VALUE)
        assertFalse(r.redacted)
    }

    @Test
    fun theSameWordingUnderAnOrdinaryColumnIsStillAValue() {
        val r = row("notes", RecordRevisionRedaction.HAD_VALUE, RecordRevisionRedaction.CLEARED)
        assertFalse(
            "the field name gates the vocabulary; without that gate a real note is presented as a non-value",
            r.redacted
        )
        assertEquals(RecordRevisionRedaction.HAD_VALUE, r.old)
        assertEquals(RecordRevisionRedaction.CLEARED, r.new)
    }

    @Test
    fun anUnmaskedAadhaarNeverReachesTheScreen() {
        val aadhaar = "123456789012"
        val cleared = row("aadhaarNumber", aadhaar, null)
        assertTrue(cleared.redacted)
        assertEquals(RecordRevisionRedaction.HAD_VALUE, cleared.old)
        assertEquals(RecordRevisionRedaction.CLEARED, cleared.new)
        assertNoDigitOf(aadhaar, cleared)

        val replaced = row("aadhaarNumber", aadhaar, "999988887777")
        assertTrue(replaced.redacted)
        assertEquals(RecordRevisionRedaction.HAD_VALUE, replaced.old)
        assertEquals(RecordRevisionRedaction.REPLACED, replaced.new)
        assertNoDigitOf(aadhaar, replaced)
        assertNoDigitOf("999988887777", replaced)
    }

    @Test
    fun anUnmaskedPehchanCardNeverReachesTheScreen() {
        val card = "PEH-4417-2290"
        val filled = row("pehchanCardNumber", null, card)
        assertTrue(filled.redacted)
        assertEquals(RecordRevisionRedaction.EMPTY, filled.old)
        assertEquals(RecordRevisionRedaction.HAD_VALUE, filled.new)
        assertFalse("${filled.old} -> ${filled.new} still carries the card number", filled.text().contains(card))
    }

    @Test
    fun aBlankToNullChurnUnderAnIdentityColumnSaysNothingWasRetracted() {
        val r = row("aadhaarNumber", "   ", "")
        assertTrue(r.redacted)
        assertEquals(RecordRevisionRedaction.EMPTY, r.old)
        assertEquals(RecordRevisionRedaction.STILL_EMPTY, r.new)
    }

    @Test
    fun aContainerUnderAnIdentityColumnIsNotPrintedEither() {
        val blob: JsonObject = buildJsonObject {
            put("note", JsonPrimitive("123456789012"))
        }
        val r = recordRevisionChangeRow("aadhaarNumber", RevisionChange(old = blob, new = JsonNull))
        assertTrue(r.redacted)
        assertEquals(RecordRevisionRedaction.HAD_VALUE, r.old)
        assertEquals(RecordRevisionRedaction.CLEARED, r.new)
        assertNoDigitOf("123456789012", r)
        assertFalse("the container's keys crossed too", r.text().contains("note"))
    }

    @Test
    fun theOtherThreeColumnsKeepWhatTheServerChoseToSend() {
        val r = row("phone", "9876543210", null)
        assertFalse(r.redacted)
        assertEquals("9876543210", r.old)
        assertEquals(RECORD_EDIT_HISTORY_ABSENT, r.new)
    }

    private fun RecordRevisionChangeRow.text() = "$field: $old → $new"

    private fun assertNoDigitOf(secret: String, r: RecordRevisionChangeRow) {
        assertFalse("the row still carries \"$secret\": ${r.text()}", r.text().contains(secret))
        for (start in 0..(secret.length - 4)) {
            val chunk = secret.substring(start, start + 4)
            assertFalse("the row leaks \"$chunk\" of \"$secret\": ${r.text()}", r.text().contains(chunk))
        }
    }

    @Test
    fun theFieldSetStillMatchesTheBackend() {
        val source = backendFile("app/services/access.py")
        val block = Regex("""REVISION_REDACTED_FIELDS\s*=\s*\{([^}]*)}""").find(source)
        assertTrue(
            "access.REVISION_REDACTED_FIELDS is no longer a literal set — RecordRevisionRedaction.FIELDS " +
                "mirrors it, so re-read the backend and update this test rather than deleting it",
            block != null
        )
        val fields = Regex("\"([A-Za-z][A-Za-z0-9_]*)\"").findAll(block!!.groupValues[1])
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "the handset's copy of the redacted columns has drifted from the server's",
            fields,
            RecordRevisionRedaction.FIELDS
        )
    }

    @Test
    fun theFiveWordingsStillMatchTheBackend() {
        val source = backendFile("app/services/access.py")
        fun python(name: String): String {
            val m = Regex(name + "\\s*=\\s*\"([^\"]*)\"").find(source)
            assertTrue("access.$name is gone; the placeholder vocabulary moved", m != null)
            return m!!.groupValues[1]
        }
        assertEquals(python("_REDACTED_HAD_VALUE"), RecordRevisionRedaction.HAD_VALUE)
        assertEquals(python("_REDACTED_CLEARED"), RecordRevisionRedaction.CLEARED)
        assertEquals(python("_REDACTED_REPLACED"), RecordRevisionRedaction.REPLACED)
        assertEquals(python("_REDACTED_EMPTY"), RecordRevisionRedaction.EMPTY)
        assertEquals(python("_REDACTED_STILL_EMPTY"), RecordRevisionRedaction.STILL_EMPTY)
    }

    @Test
    fun theKeyMaskedColumnsStillMatchTheBackendReadPath() {
        val source = backendFile("app/services/records.py")
        val m = Regex("""_IDENTITY_KEYS\s*=\s*\(([^)]*)\)""").find(source)
        assertTrue("records._IDENTITY_KEYS is no longer a literal tuple", m != null)
        val keys = Regex("\"([A-Za-z][A-Za-z0-9_]*)\"").findAll(m!!.groupValues[1])
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "RecordRevisionRedaction.SERVER_MASKED_FIELDS is the set the read path masks by key name; " +
                "it has drifted from records._IDENTITY_KEYS",
            keys,
            RecordRevisionRedaction.SERVER_MASKED_FIELDS
        )
        assertTrue(
            "every key-masked column must also be a redacted column",
            RecordRevisionRedaction.FIELDS.containsAll(RecordRevisionRedaction.SERVER_MASKED_FIELDS)
        )
    }

    @Test
    fun theCaptionStillMatchesTheWebWordForWord() {
        val panel = repoFile("frontend/components/CollabPanel.tsx")
        val m = Regex("""<p className="text-xs text-ink-muted">([\s\S]*?)</p>""").find(panel)
        assertTrue("CollabPanel's edit-history caption paragraph moved; re-read it", m != null)
        val web = m!!.groupValues[1]
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
        assertEquals(
            "the handset caption and the web caption no longer say the same thing",
            web,
            RECORD_EDIT_HISTORY_CAPTION
        )
        for (name in listOf("Aadhaar", "Pehchan card", "phone", "email", "address")) {
            assertTrue("the caption stopped naming $name", RECORD_EDIT_HISTORY_CAPTION.contains(name))
        }
        assertTrue(
            "the caption must say outright that the value is never recorded",
            RECORD_EDIT_HISTORY_CAPTION.contains("never the value")
        )
    }

    @Test
    fun changeRowsKeepTheOrderTheServerSentThem() {
        val changes = linkedMapOf(
            "phone" to RevisionChange(old = str("(value recorded)"), new = str("(cleared)")),
            "localName" to RevisionChange(old = str("Kutchi"), new = str("Kachchhi")),
            "notes" to RevisionChange(old = JsonNull, new = str("Met at the mela."))
        )
        assertEquals(
            listOf("phone", "localName", "notes"),
            recordRevisionRows(changes).map { it.field }
        )
    }

    @Test
    fun theEmptyStateAndTheUnknownEditorMatchTheWeb() {
        assertEquals("No edits recorded.", RECORD_EDIT_HISTORY_EMPTY)
        assertEquals("Edit history", RECORD_EDIT_HISTORY_TITLE)
        assertEquals("Unknown", RECORD_EDIT_HISTORY_UNKNOWN_EDITOR)
        assertEquals("—", RECORD_EDIT_HISTORY_ABSENT)
    }

    @Test
    fun theEditorLineNamesWhoAndWhenAndFallsBackRatherThanBlanking() {
        val zone = ZoneId.of("Asia/Kolkata")
        val locale = Locale.UK
        val named = revision(editor = "Asha Devi", at = "2026-08-24T09:30:00+00:00")
        val line = recordRevisionEditorLine(named, zone, locale)
        assertTrue(line, line.startsWith("Asha Devi · 24 Aug 2026, "))
        assertTrue("09:30 UTC is 15:00 in Kolkata: $line", line.contains("03:00"))

        val anonymous = revision(editor = null, at = "2026-08-24T09:30:00+00:00")
        assertTrue(recordRevisionEditorLine(anonymous, zone, locale).startsWith("Unknown · "))

        val odd = revision(editor = "Asha Devi", at = "whenever")
        assertEquals("Asha Devi · whenever", recordRevisionEditorLine(odd, zone, locale))
    }

    private fun revision(editor: String?, at: String) = RecordRevisionDto(
        id = "rev-1",
        recordType = "artisan",
        recordId = "art-1",
        editedBy = editor?.let {
            UserDto(id = "u-1", email = "a@example.org", name = it, role = "RESEARCHER")
        },
        changes = emptyMap(),
        createdAt = at
    )

    private fun repoFile(path: String): String {
        val file = File("../../$path")
        assertTrue(
            "expected $path at ${file.absolutePath}. This test pins the handset's copy of a " +
                "redaction decision against the original; if the tree moved, fix the path — do not " +
                "delete the assertion, because a silent copy is exactly how this leaks.",
            file.exists()
        )
        return file.readText(Charsets.UTF_8)
    }

    private fun backendFile(path: String): String = repoFile("backend/$path")
}
