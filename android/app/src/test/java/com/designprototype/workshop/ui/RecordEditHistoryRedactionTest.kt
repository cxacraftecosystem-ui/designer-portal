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

/**
 * THE ONE THING ON THIS SCREEN THAT MUST NEVER REGRESS: an identity column's ledger row does not
 * print the identity.
 *
 * ── WHY THIS FILE, AND WHY IT IS NOT A UI TEST ────────────────────────────────────────────────
 *
 * `RecordEditHistory` renders the append-only `RecordRevision` ledger, in which five columns —
 * `aadhaarNumber`, `pehchanCardNumber`, `phone`, `email`, `address` — are logged by the server
 * WITHOUT their value (`access.REVISION_REDACTED_FIELDS`). Getting that wrong does not raise, log
 * or fail a build. It prints twelve digits of somebody's Aadhaar in a panel an admin opened to see
 * who edited what, and the only person who would ever notice is the one it belongs to.
 *
 * Nothing here composes anything, and it does not need to: every decision that could leak was
 * lifted out of the composable into pure functions precisely so it could be asserted. What this
 * file pins is the classification and the resulting text. The INK the classification selects — the
 * red/strikethrough for a value, the muted italic for a placeholder — is reading-only, and is
 * stated as such rather than claimed as covered.
 *
 * ── THE FOUR PROPERTIES ───────────────────────────────────────────────────────────────────────
 *
 *   1. Every pair the server can write for a redacted column is recognised, on every one of the
 *      five columns, and survives to the screen in the server's own words.
 *   2. The CLOSED SET is the test — not the flag beside it, not one half of the pair, and not the
 *      wording appearing under some other column. This is `records._mask_identity_node`'s own rule
 *      ("THE PAIR IS CHECKED AGAINST THE CLOSED SET RATHER THAN THE FLAG BELIEVED") ported.
 *   3. Under the two columns the read path masks by key name, no digit of an unmasked value can
 *      reach the screen even if one arrives — because this .apk outlives the server it was built
 *      against.
 *   4. The Kotlin copies of the server's field set and the server's five wordings still match the
 *      Python, and the caption still matches the web's. Three transcriptions of one decision, kept
 *      honest by reading the originals rather than by remembering them.
 */
class RecordEditHistoryRedactionTest {

    private fun str(value: String?) = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun row(field: String, old: String?, new: String?) =
        recordRevisionChangeRow(field, RevisionChange(old = str(old), new = str(new)))

    // ---------------------------------------------------------------------------------------------
    // 1. Every server placeholder, on every redacted column.
    // ---------------------------------------------------------------------------------------------

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
                // The server's words reach the screen untouched. Re-wording them here would put a
                // sixth vocabulary in front of a reader who may also be looking at the web panel.
                assertEquals(old, r.old)
                assertEquals(new, r.new)
            }
        }
    }

    @Test
    fun noPlaceholderPairSaysTheSameThingOnBothSides() {
        // access._redacted_change's docstring: four distinct wordings exist so that "a consumer that
        // decides whether to draw a row by diffing the two still sees a change on every one of
        // them, which a shared '(redacted)' placeholder on both sides would have hidden."
        for ((old, new) in RecordRevisionRedaction.PLACEHOLDER_PAIRS) {
            assertFalse("a placeholder pair that reads the same twice hides the edit", old == new)
        }
        assertEquals(
            "the closed set is four transitions; a fifth means access.py grew one and this copy did not",
            4,
            RecordRevisionRedaction.PLACEHOLDER_PAIRS.size
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 2. The closed set is the test.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun halfOfAPlaceholderPairIsNotAPlaceholder() {
        // "(value recorded)" arriving beside a real number is NOT one of the server's transitions.
        // Matching on `old` alone would dress this row as a redaction and quietly hide the number
        // in `new` behind a claim that nothing was recorded.
        val r = row("phone", RecordRevisionRedaction.HAD_VALUE, "9876543210")
        assertFalse(r.redacted)
        assertEquals("9876543210", r.new)
    }

    @Test
    fun aReversedPairIsNotAPlaceholder() {
        // "(cleared)" -> "(value recorded)" is not a transition access._redacted_change can write.
        val r = row("email", RecordRevisionRedaction.CLEARED, RecordRevisionRedaction.HAD_VALUE)
        assertFalse(r.redacted)
    }

    @Test
    fun theSameWordingUnderAnOrdinaryColumnIsStillAValue() {
        // `notes` is deliberately NOT in REVISION_REDACTED_FIELDS — the backend argument is that the
        // old text is the only way to see what a malicious edit quietly removed. A note that happens
        // to read "(value recorded)" is a note, and must be shown as one.
        val r = row("notes", RecordRevisionRedaction.HAD_VALUE, RecordRevisionRedaction.CLEARED)
        assertFalse(
            "the field name gates the vocabulary; without that gate a real note is presented as a non-value",
            r.redacted
        )
        assertEquals(RecordRevisionRedaction.HAD_VALUE, r.old)
        assertEquals(RecordRevisionRedaction.CLEARED, r.new)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. No digit of an unmasked identity value reaches the screen.
    // ---------------------------------------------------------------------------------------------

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
        // deps.is_empty_value treats "" and "   " as empty, and values_match(None, "") is False, so
        // a payload sending `aadhaarNumber: ""` at an already-NULL column produces a real ledger
        // row. It must NOT read "(cleared)": that asserts a retraction on a column that held
        // nothing, on the one screen an admin reads to find out what was done to a record.
        val r = row("aadhaarNumber", "   ", "")
        assertTrue(r.redacted)
        assertEquals(RecordRevisionRedaction.EMPTY, r.old)
        assertEquals(RecordRevisionRedaction.STILL_EMPTY, r.new)
    }

    @Test
    fun aContainerUnderAnIdentityColumnIsNotPrintedEither() {
        // records._mask_identity_node replaces any unrecognised container under these keys with a
        // flat mask before encoding, so this shape cannot arrive from a current server. If one does
        // — an older deployment, a stale body — the object's KEYS are as disclosing as its leaves,
        // and `JsonObject.toString()` would print both.
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
        // DELIBERATE, AND THE ASYMMETRY IS THE SERVER'S, NOT THIS SCREEN'S. records._IDENTITY_KEYS
        // masks `aadhaarNumber` and `pehchanCardNumber` by key name on the way out; `phone`, `email`
        // and `address` are redacted on the WRITE path only, so a row written before
        // access.REVISION_REDACTED_FIELDS existed still arrives holding the retracted value and the
        // web panel shows it. Suppressing it here would remove nothing from the database while
        // making a handset and a laptop disagree about the same record — and widening the redaction
        // set is an owner's call on the server, which access.REVISION_REDACTED_FIELDS records as
        // re-raised. If that call is ever made, this assertion is the one that should fail first.
        val r = row("phone", "9876543210", null)
        assertFalse(r.redacted)
        assertEquals("9876543210", r.old)
        assertEquals(RECORD_EDIT_HISTORY_ABSENT, r.new)
    }

    private fun RecordRevisionChangeRow.text() = "$field: $old → $new"

    private fun assertNoDigitOf(secret: String, r: RecordRevisionChangeRow) {
        assertFalse("the row still carries \"$secret\": ${r.text()}", r.text().contains(secret))
        // Not just the whole string — any run of four or more of its characters would be a partial
        // disclosure of the same kind the backend refuses to make from a historical ledger row.
        for (start in 0..(secret.length - 4)) {
            val chunk = secret.substring(start, start + 4)
            assertFalse("the row leaks \"$chunk\" of \"$secret\": ${r.text()}", r.text().contains(chunk))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Three transcriptions of one decision, kept honest against the originals.
    // ---------------------------------------------------------------------------------------------

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
        // The caption is the parity gap routes/artisans.py logged against this client, in those
        // words: the handset "is not yet at parity on the CAPTION naming this exception". A caption
        // that drifts from the web's is two different promises about the same ledger.
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
        // And, independently of the web, it must actually name what it promises to name.
        for (name in listOf("Aadhaar", "Pehchan card", "phone", "email", "address")) {
            assertTrue("the caption stopped naming $name", RECORD_EDIT_HISTORY_CAPTION.contains(name))
        }
        assertTrue(
            "the caption must say outright that the value is never recorded",
            RECORD_EDIT_HISTORY_CAPTION.contains("never the value")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Wording, ordering and the empty state — the three things the web panel is matched on.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun changeRowsKeepTheOrderTheServerSentThem() {
        // data_access.list_revisions orders revisions createdAt ASC and the caption's claim about
        // "the FIRST before" depends on it; within one revision, `changes` decodes to a
        // LinkedHashMap and both clients walk it in wire order. An alphabetical sort here would make
        // two people reading the same edit on two devices see different rows in different places.
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
        // Asserted in pieces rather than as one literal: the AM/PM marker's case is CLDR data and
        // has changed between JDKs in this repository before (see DwWorkshopCodesTest), and a test
        // that breaks on a toolchain upgrade teaches the next reader to delete it.
        assertTrue(line, line.startsWith("Asha Devi · 24 Aug 2026, "))
        assertTrue("09:30 UTC is 15:00 in Kolkata: $line", line.contains("03:00"))

        // A deleted account leaves the join empty; the web prints "Unknown" rather than nothing,
        // because "somebody edited this and we cannot say who" is itself the finding.
        val anonymous = revision(editor = null, at = "2026-08-24T09:30:00+00:00")
        assertTrue(recordRevisionEditorLine(anonymous, zone, locale).startsWith("Unknown · "))

        // An unparseable stamp is still evidence of when something happened; it is shown raw rather
        // than blanked, which is `readableStamp`'s own rule.
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

    // ---------------------------------------------------------------------------------------------

    /**
     * Gradle runs unit tests with the module directory (`android/app`) as the working directory —
     * the same assumption `DwBulletListFieldTest` makes when it reads `src/main/assets`. Two levels
     * up is the monorepo root, where the Python and the TypeScript this file pins itself against
     * live.
     */
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
