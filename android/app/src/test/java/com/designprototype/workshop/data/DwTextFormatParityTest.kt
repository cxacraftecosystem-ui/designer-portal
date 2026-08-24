package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THIS HANDSET'S ANSWER TO `FieldSpec.text_format`, PROVED EQUAL TO THE BROWSER'S AND THE SERVER'S.
 *
 * ── WHY THE TABLE IS SHARED AND NOT WRITTEN HERE ──────────────────────────────────────────────
 *
 * `shared/text-format-vectors.json` — at the repository ROOT, so that no side owns it — is read
 * unmodified by this test, by `frontend/e2e/text-format-parity-unit.spec.ts` and by
 * `test_the_three_implementations_agree_on_the_shared_vector_table`. The same arrangement, and the
 * same reason, as [DwAnalysisParityTest]: expectations written into each side prove only that each
 * side still does what it already did. This test briefly read a SECOND copy under
 * `app/src/test/resources/`, added in this same wave before the shared file existed; converging on
 * one was the first thing the comparison found, and the second copy is gone.
 *
 * ONLY `vectors` IS READ. The table also carries `server_only`, whose rows this test must NOT
 * assert: they are values no client CONTROL can produce (`ArtisanPhoneField` accepts only digits, so
 * it cannot compose "not a number"), and asserting them would demand a rule this app has no way to
 * reach. The direction of that disagreement is the safe one — the server refuses, and the refusal
 * arrives as a [DwStageRefusal] against this exact box.
 *
 * ── WHAT WENT WRONG WITHOUT IT ────────────────────────────────────────────────────────────────
 *
 * Email had three implementations of one rule, in three languages, each carrying a comment claiming
 * parity with the other two, and all three answers were different. THIS FILE'S OWN CLIENT was the
 * strictest: [DwValues.coerce]'s EMAIL arm refused a missing `@` and a leading or trailing `@`,
 * where the browser refused nothing at all on a stage box and the server refused nothing anywhere,
 * on any path. So this phone rejected addresses the repository would have accepted and accepted
 * addresses with no domain at all ("designer@dch"), and nobody knew, because a malformed address
 * carries perfectly well until somebody tries to write to it.
 *
 * That arm is gone. The rule is now a DECLARATION on the field, answered by [DwTextFormats], and
 * this test is the thing that stops the three drifting apart again.
 *
 * ── AND WHY IT MATTERS MORE HERE THAN ANYWHERE ────────────────────────────────────────────────
 *
 * A handset is the client that goes out of signal. The schema is read from disk for as long as the
 * device is away, so a rule this app gets wrong is a rule it goes on getting wrong for a fortnight —
 * and the designer meets the refusal as a sync card about a stage they finished in another district,
 * with the person whose number it was three hundred kilometres behind them.
 */
class DwTextFormatParityTest {

    private data class Case(val format: String, val value: String, val expected: String?, val note: String?)

    /**
     * The shared table, found by walking up from the module rather than through the classpath.
     *
     * It deliberately does NOT live in `app/src/test/resources`: it is a three-language contract, and
     * a file inside one client's test resources reads as that client's fixture. `DwAnalysisParityTest`
     * keeps its tables under resources because they are GOLDENS regenerated from the backend; this
     * one is a hand-written contract all three sides answer to, so it sits at the repository root.
     */
    private fun tableFile(): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "shared/text-format-vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("shared/text-format-vectors.json was not found above ${java.io.File("").absolutePath}")
    }

    private fun cases(): List<Case> {
        val root = Json.parseToJsonElement(tableFile().readText()).jsonObject
        return root["vectors"]!!.jsonArray.map { element ->
            val row: JsonObject = element.jsonObject
            Case(
                format = row["format"]!!.jsonPrimitive.content,
                value = row["value"]!!.jsonPrimitive.content,
                // `JsonNull` is a legitimate expectation — "this value is fine" — and reading it as a
                // missing key would turn every accept case into a silent skip, which is exactly how a
                // parity table comes to pass while proving nothing.
                expected = row["error"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.contentOrNull },
                note = row["note"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    @Test
    fun `the shared table is not empty and covers every format this build knows`() {
        // A table that failed to load, or one somebody emptied while regenerating it, would make the
        // assertion below pass vacuously — the one way a parity test can lie.
        val rows = cases()
        assertTrue("the shared case table is suspiciously small: ${rows.size} rows", rows.size > 30)
        val covered = rows.map { it.format }.toSet()
        for (format in listOf("EMAIL", "PHONE_IN", "AADHAAR", "PEHCHAN", "PINCODE")) {
            assertTrue("no case for $format", format in covered)
        }
    }

    @Test
    fun `the handset and the browser agree on the shared vector table`() {
        val disagreements = cases().mapNotNull { row ->
            val got = DwTextFormats.error(row.format, row.value)
            if (got == row.expected) null else buildString {
                append("format=${row.format.ifEmpty { "(none)" }} value=${'"'}${row.value}${'"'}\n")
                append("  expected: ${row.expected}\n")
                append("  got:      $got")
                row.note?.let { append("\n  the case is here because: $it") }
            }
        }
        // Reported all at once rather than failing on the first: a divergence is usually a whole ARM
        // being wrong, and seeing one row of six tells you far less than seeing the six.
        assertEquals("the handset does not match the shared table", "", disagreements.joinToString("\n\n"))
    }

    @Test
    fun `a typed string that is not an aadhaar is refused rather than masked`() {
        /*
         * THE CASE THE WHOLE FEATURE WAS BUILT FOR, ASSERTED AGAINST [DwValues.coerce] AND NOT ONLY
         * AGAINST THE RULE — because the rule being right is not the property that matters. What
         * matters is that the rule runs BEFORE the mask in the arm that stores the value.
         *
         * [ArtisanIdentity.mask] takes the last four characters of ANYTHING once separators come off,
         * so "hello world 1234" is fourteen characters, passes `maxLength = 20`, and becomes
         * "XXXX XXXX 1234" — indistinguishable from a real number, in a document submitted to a
         * ministry. With the two steps the other way round the check does not merely fail to help; it
         * MANUFACTURES the defect it exists to prevent.
         */
        val field = FieldDto(
            key = "aadhaarNumber",
            label = "Aadhaar number",
            type = "TEXT",
            maxLength = 20,
            storeMasked = true,
            format = "AADHAAR",
        )
        val refused = DwValues.coerce(field, "hello world 1234")
        assertEquals(null, refused.value)
        assertEquals("Aadhaar number must be 12 digits — remove any letters or symbols.", refused.error)
    }

    @Test
    fun `a hydrated mask survives re-coercion without a refusal`() {
        /*
         * `hydrate_entries` writes a masked identity number into this box, and both `validate_entry`
         * and [DwValues.validate] re-coerce every field on every save. A rule that refused the mask
         * would put a red error on every hydrated row on every save FOR EVER — `save_stage` restores
         * the refused key from `previous`, so the value would never change and the error would never
         * clear — naming a fault the designer cannot fix because the digits are not theirs to see.
         *
         * And the value must come back BYTE-IDENTICAL: the mask function is idempotent, which is what
         * makes a silent re-save possible at all.
         */
        val field = FieldDto(
            key = "aadhaarNumber",
            label = "Aadhaar number",
            type = "TEXT",
            maxLength = 20,
            storeMasked = true,
            format = "AADHAAR",
        )
        val accepted = DwValues.coerce(field, "XXXX XXXX 0124")
        assertEquals(null, accepted.error)
        assertEquals("XXXX XXXX 0124", DwValues.text(accepted.value))
    }

    @Test
    fun `the mask predicate refuses what an X anywhere would accept`() {
        /*
         * `isMaskedIdentityNumber` / `is_masked_aadhaar` answer "an X anywhere", and that rule is
         * correct where it is used: `ArtisanUpdate` DROPS the key before the write, so a false
         * positive costs a skipped update. Here whatever is accepted is STORED and then masked, so
         * under "an X anywhere" this value becomes "XXXX XXXX 1234" — the original defect, behind a
         * validator that reports it clean.
         */
        assertEquals(
            "Aadhaar number must be 12 digits — remove any letters or symbols.",
            DwTextFormats.aadhaarOrMaskError("XxamplE 1234"),
        )
        assertEquals(
            "Aadhaar number must be 12 digits — remove any letters or symbols.",
            DwTextFormats.aadhaarOrMaskError("X"),
        )
    }

    @Test
    fun `an over-length answer keeps its own refusal rather than a format complaint`() {
        /*
         * LENGTH BEFORE FORMAT, matching the server. An over-long answer has a specific, actionable
         * fault of its own; handing it to the format rule instead would tell the designer their email
         * address is malformed when what is wrong with it is that it is forty characters too long.
         */
        val field = FieldDto(key = "email", label = "Email", type = "EMAIL", maxLength = 10, format = "EMAIL")
        val refused = DwValues.coerce(field, "designer@dch.gov.in")
        assertEquals("Email is longer than 10 characters", refused.error)
    }

    @Test
    fun `a format nothing in this build knows enforces nothing`() {
        /*
         * A server one release ahead can declare a format this build has never heard of. The server's
         * own dispatch refuses an unmapped member AT INSTALL — a published flag nothing enforces is
         * the silent failure the feature exists to end — and this client deliberately cannot make the
         * same refusal, because the two ways of being wrong are not symmetrical: enforcing nothing
         * costs one sync and stores nothing wrong, while guessing a rule refuses an answer the server
         * would have taken, on a stage a designer cannot get past, on a device with no signal.
         */
        val field = FieldDto(key = "gstin", label = "GSTIN", type = "TEXT", format = "GSTIN")
        val coerced = DwValues.coerce(field, "not a gstin")
        assertEquals(null, coerced.error)
        assertEquals("not a gstin", DwValues.text(coerced.value))
    }
}
