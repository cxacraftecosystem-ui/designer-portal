package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.notKeptSuffix
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep the identity-card photograph, or discard it — and the rules that make "discard" mean it.
 *
 * ── THE FAILURE THIS FILE EXISTS TO CATCH ─────────────────────────────────────────────────────
 *
 * A phone that keeps the image while the browser discards it, or the reverse. The brief that added
 * this feature was explicit that the two clients must not disagree, and the direction they have to
 * agree in is the safe one: a photograph that was NOT kept can be retaken in ten seconds with the
 * card still in the artisan's hand, whereas one kept by accident is a regulated document in a
 * research bucket that nobody knows to go and delete. Every assertion below is a way that agreement
 * could quietly break.
 *
 * ── WHERE THE FIXTURES COME FROM ──────────────────────────────────────────────────────────────
 *
 * The `photograph` block and the retention route's two reply shapes are transcribed from the route
 * bodies in `backend/app/api/routes/design_workshops.py` (`scan_identity_card` and
 * `decide_identity_photograph`) and cross-checked against the assertions in
 * `backend/tests/test_identity_photo_retention.py`, which pins `deleted`, `retention is None` on a
 * discard and `deleted is False` on a store. Both were read on 2026-08-16.
 *
 * The most valuable thing in them is what the STORE branch does NOT send: it returns four keys and
 * `objectDeleted` is not one of them. A DTO that required it would turn a successful "keep this
 * photograph" into a deserialisation crash on the panel that was about to name the person who
 * decided — see [a store reply omits objectDeleted entirely and must still decode].
 */
class DwIdentityRetentionTest {

    /** Configured exactly as `ApiClient` configures the converter Retrofit uses. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    // ── parse_retention, ported ──────────────────────────────────────────────────────────────────

    /**
     * The two words are the whole vocabulary, and case and padding are forgiven.
     *
     * Forgiven rather than refused because they are not ambiguity: "store", "STORE" and " Store "
     * are one intention, and refusing them would only teach the next caller to send something else.
     */
    @Test
    fun `the two decisions round-trip through every spelling of themselves`() {
        assertEquals(DW_RETENTION_STORE, parseRetention("STORE"))
        assertEquals(DW_RETENTION_STORE, parseRetention("store"))
        assertEquals(DW_RETENTION_STORE, parseRetention("  Store  "))
        assertEquals(DW_RETENTION_DISCARD, parseRetention("DISCARD"))
        assertEquals(DW_RETENTION_DISCARD, parseRetention("discard"))
    }

    /**
     * EVERYTHING ELSE IS DISCARD, AND NOTHING THROWS.
     *
     * This is the safety property of the entire feature, and it is a deliberate copy of the
     * server's `parse_retention` — including the argument for why an unparseable answer is not a
     * refusal. The two ways to handle a value the code cannot act on are "keep the identity document
     * and let somebody sort it out" or "keep nothing", and only one of those is recoverable.
     *
     * `"keep"` is in the list on purpose. It is the word a well-meaning contributor would reach for,
     * it reads as STORE to a human, and it must not be one — a client that started sending it would
     * otherwise silently retain every card it photographed while its author believed the feature was
     * working.
     */
    @Test
    fun `every unrecognised value resolves to discard rather than raising`() {
        listOf(null, "", "   ", "keep", "KEEP", "true", "1", "yes", "None", "delete", "STORED", "st ore")
            .forEach { raw ->
                assertEquals(
                    "“$raw” must not be able to retain an identity document",
                    DW_RETENTION_DISCARD,
                    parseRetention(raw),
                )
            }
    }

    /** The named default is the safe half. One place to look when checking that. */
    @Test
    fun `the default is discard`() {
        assertEquals(DW_RETENTION_DISCARD, DW_RETENTION_DEFAULT)
    }

    // ── What the server said it did with the picture ─────────────────────────────────────────────

    /**
     * `stored: false` is a promise; a MISSING `photograph` block is not.
     *
     * The temptation is to read absence as false, because that is in fact what every deployment
     * older than the field does. That would be a screen telling a designer "your photograph was not
     * kept" on the strength of a key that was not there — a promise about regulated data made from
     * an absence. The web enforces the identical distinction in its own `photographWasNotStored`,
     * and the two must not drift: a designer comparing the phone and the browser against the same
     * deployment is entitled to read the same promise.
     */
    @Test
    fun `only an explicit stored-false counts as the server having promised anything`() {
        val explicit = json.decodeFromString(
            DwIdentityOcrDto.serializer(),
            """{"aadhaarCandidates":[],"pehchanCandidates":[],"rejectedAadhaarCount":0,
                "provider":"openai","requiresConfirmation":true,
                "photograph":{"stored":false,"retention":"DISCARD",
                              "decisionRoute":"/design-workshops/ocr/identity/retention"}}"""
        )
        assertTrue(photographWasNotStored(explicit))
        assertEquals("DISCARD", explicit.photograph?.retention)
        assertEquals("/design-workshops/ocr/identity/retention", explicit.photograph?.decisionRoute)

        val olderDeployment = json.decodeFromString(
            DwIdentityOcrDto.serializer(),
            """{"aadhaarCandidates":[],"pehchanCandidates":[],"rejectedAadhaarCount":0,
                "provider":"openai","requiresConfirmation":true}"""
        )
        assertNull(olderDeployment.photograph)
        assertFalse(
            "a missing key must produce silence, never a promise",
            photographWasNotStored(olderDeployment),
        )
    }

    /** The declared retention is echoed back, so a screen reports what was asked rather than assuming. */
    @Test
    fun `a store declaration is echoed while stored stays false`() {
        val result = json.decodeFromString(
            DwIdentityOcrDto.serializer(),
            """{"photograph":{"stored":false,"retention":"STORE",
                "decisionRoute":"/design-workshops/ocr/identity/retention"}}"""
        )
        assertEquals("STORE", result.photograph?.retention)
        assertTrue(
            "the route has no storage path whatever the caller declares",
            photographWasNotStored(result),
        )
    }

    // ── The retention route's two replies ────────────────────────────────────────────────────────

    /**
     * A discard reports BOTH halves separately, because "deleted" alone would be true of a soft one.
     *
     * `design_workshops.py` opens by stating that nothing in it hard-deletes, and this route is the
     * stated exception. `objectDeleted` is what makes the claim checkable from the client: a reply
     * saying `deleted: true, objectDeleted: false` would mean the row is gone and the JPEG is still
     * in the bucket — which is the exact definition of merely hiding, and the state
     * `media.delete_media`'s ordering can reach.
     */
    @Test
    fun `a discard reply says the row went and says separately that the object did`() {
        val result = json.decodeFromString(
            DwRetentionResultDto.serializer(),
            """{"mediaId":"med_1","decision":"DISCARD","deleted":true,"objectDeleted":true,
                "retention":null}"""
        )
        assertEquals(DW_RETENTION_DISCARD, result.decision)
        assertTrue(result.deleted)
        assertTrue(result.objectDeleted)
        assertNull(
            "a row saying 'this was discarded' would be a row that still names the photograph",
            result.retention,
        )
    }

    /**
     * A store reply omits `objectDeleted` entirely and must still decode.
     *
     * The route returns exactly four keys on the STORE branch — `mediaId`, `decision`, `deleted`,
     * `retention` — and `objectDeleted` is not among them. Required here, this reply would throw
     * inside the coroutine that was about to tell the designer whose name went on the decision, and
     * the failure would look like "keeping the photograph failed" about a photograph that was kept.
     */
    @Test
    fun `a store reply omits objectDeleted entirely and must still decode`() {
        val result = json.decodeFromString(
            DwRetentionResultDto.serializer(),
            """{"mediaId":"med_1","decision":"STORE","deleted":false,
                "retention":{"decision":"STORE","decidedById":"usr_1",
                             "decidedByName":"Asha Devi","decidedAt":"2026-08-16T09:30:00+00:00"}}"""
        )
        assertEquals(DW_RETENTION_STORE, result.decision)
        assertFalse(result.deleted)
        assertFalse("absent must read as false, not crash", result.objectDeleted)
        assertEquals("Asha Devi", result.retention?.decidedByName)
    }

    // ── What the designer is told afterwards ─────────────────────────────────────────────────────

    /**
     * "Deleted" is said without hedging, because the route means it.
     *
     * A sentence like "the photograph has been removed from view" would describe a soft delete. This
     * route deletes the object and then the row, and refuses the whole request rather than deleting
     * the row with the object still in the bucket — so the sentence is entitled to be flat, and a
     * softer one would understate a guarantee somebody paid for with a 502 branch.
     */
    @Test
    fun `the discard sentence says the file and the record are both gone`() {
        val sentence = retentionOutcomeSentence(
            DwRetentionResultDto(mediaId = "med_1", decision = DW_RETENTION_DISCARD, deleted = true)
        )
        assertTrue(sentence.contains("both gone"))
        assertTrue("the confirmed number is unaffected and must be said", sentence.contains("number"))
    }

    /**
     * The store sentence NAMES the person, and says the photograph is unmasked.
     *
     * Both halves are load-bearing. The name is the entire purpose of the stamp — a retained
     * identity document that cannot be traced to whoever decided to retain it is indistinguishable
     * from one nobody agreed to. And "unmasked" is the fact a designer is least likely to have
     * thought about: the NUMBER is masked on every surface this repository shows, and the
     * photograph of it is not, because no masking function can touch a JPEG.
     */
    @Test
    fun `the store sentence names the decider and says the photograph is unmasked`() {
        val sentence = retentionOutcomeSentence(
            DwRetentionResultDto(
                mediaId = "med_1",
                decision = DW_RETENTION_STORE,
                deleted = false,
                retention = DwRetentionStampDto(
                    decision = DW_RETENTION_STORE,
                    decidedById = "usr_1",
                    decidedByName = "Asha Devi",
                ),
            )
        )
        assertTrue(sentence.contains("Asha Devi"))
        assertTrue(sentence.contains("unmasked"))
    }

    /** A stamp with no name still produces a sentence, and still says the photograph is unmasked. */
    @Test
    fun `a nameless stamp degrades to the same warning without an empty possessive`() {
        val sentence = retentionOutcomeSentence(
            DwRetentionResultDto(decision = DW_RETENTION_STORE, retention = DwRetentionStampDto())
        )
        assertFalse("“in 's name” would be the giveaway", sentence.contains("'s name"))
        assertTrue(sentence.contains("unmasked"))
    }

    /** The pre-shutter blurb is only reassuring on the half that deserves it. */
    @Test
    fun `the choice blurb warns when keeping and reassures when not`() {
        assertTrue(retentionChoiceBlurb(keep = true).contains("unmasked"))
        assertTrue(retentionChoiceBlurb(keep = true).contains("KEPT"))
        assertTrue(retentionChoiceBlurb(keep = false).contains("not kept"))
    }

    /**
     * The "not kept" promise is withheld exactly where a switch could contradict it.
     *
     * Before this feature the control ended every description with "The photograph is not kept."
     * unconditionally. That was true then and is not true on a surface that offers to keep it, and a
     * line contradicting the switch two rows above it is how a reader comes to trust the wrong half.
     * Where there is genuinely no way to keep anything the promise is still made, because that is
     * the surface where it is worth the most.
     */
    @Test
    fun `the not-kept promise is made only where nothing can be kept`() {
        assertTrue(notKeptSuffix(canKeep = false).contains("not kept"))
        assertEquals("", notKeptSuffix(canKeep = true))
    }
}
