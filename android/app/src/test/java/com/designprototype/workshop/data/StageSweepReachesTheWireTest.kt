package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE GATE HAS TO REACH THE WIRE, AND FOR ONE RELEASE IT DID NOT.
 *
 * `buildStageBody` decides `replaceCollections = authoritative`, and every test of that decision —
 * including the four in `StageAuthorityEarnedByReadingTest` — reads it off the Kotlin object, where it
 * has been correct all along. The wire is a second question and nothing asked it:
 *
 *  * `StageSaveBody.replaceCollections` carried `= false`, and kotlinx OMITS a property that still
 *    holds its default, because `ApiClient.retrofit`'s `Json { … }` never sets `encodeDefaults`;
 *  * the server's `StageSaveIn.replaceCollections` is `Field(default=True)`
 *    (`backend/app/schemas/design_workshops.py:198`).
 *
 * So "do not sweep" was spelled as silence, and silence up there means SWEEP EVERYTHING THIS PAYLOAD
 * NAMES. Measured with this handset's own builder against the running API and a live Postgres — a
 * draft with `stageSeen = false` holding one `tool` row:
 *
 *   body {"entries":[{"entityKey":"tool","ordinal":0,
 *                     "data":{"name":"Pit loom (corrected)","_clientKey":"phone-tool-1"},
 *                     "merge":true}]}
 *   -> HTTP 200 {"saved":1,"created":0,"updated":1,"removed":3,"errors":{}}
 *
 * Three rows the phone had never downloaded, deleted by a save that had correctly worked out it was
 * not entitled to delete anything. That is the whole fortnight-of-process-steps failure
 * [StageDraft.stageSeen] exists to prevent, arriving through the serialiser instead of through the
 * builder — and it needs no deletion, no fold and no second device: every first save of a stage this
 * phone has not read carries it.
 *
 * ── WHY THE ASSERTIONS ARE ON A STRING ───────────────────────────────────────────────────────────
 *
 * Because the defect is invisible in the object. A test reading `body.replaceCollections` passes for
 * both the broken and the fixed build, which is exactly what 1107 of them did. The only thing that can
 * fail for this is the JSON the server will actually parse, so these tests encode with the SAME
 * configuration `ApiClient` uses and read the bytes.
 *
 * ── AND WHY `encodeDefaults = true` IS NOT THE FIX ───────────────────────────────────────────────
 *
 * It would put `"merge":false` on every entry of every save, and an API that predates that field is
 * `extra="forbid"`, so it answers 422 "Extra inputs are not permitted" to the lot — see
 * [StageEntryBody.merge], which documents the omission as deliberate and load-bearing. The two rules
 * are opposite and both are true, so this file pins BOTH: the flag must be present, the merge must be
 * absent. A future reader who turns the switch on to fix one will fail the other.
 */
class StageSweepReachesTheWireTest {

    /**
     * `ApiClient.retrofit`'s converter, copied field for field.
     *
     * Copied rather than imported because `ApiClient.retrofit` needs a `TokenStore` and builds an
     * OkHttp stack; what matters here is the four switches it sets and the one it does not, and a copy
     * that drifted would be caught by [the wire body carries the flag] failing rather than passing.
     */
    private val wire = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private val spec = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "tool", cardinality = "COLLECTION", title = "Tool",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT")),
            ),
        ),
    )

    private fun draft(seen: Boolean, emptied: List<String> = emptyList()) = StageDraft(
        stageId = spec.key,
        rows = listOf(
            DraftRow(id = dwRowId("tool", "row-1"), values = mapOf("name" to JsonPrimitive("Pit loom"))),
        ),
        stageSeen = seen,
        emptiedEntities = emptied,
    )

    private fun encode(seen: Boolean, emptied: List<String> = emptyList()): String {
        val stored = draft(seen, emptied)
        val built = buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, null))
        return wire.encodeToString(StageSaveBody.serializer(), built.body)
    }

    @Test
    fun `a stage this phone has never read says so on the wire, in words the server reads`() {
        val body = encode(seen = false)
        assertTrue(
            "the flag must be PRESENT and false — omitting it is read as `true` by StageSaveIn: $body",
            body.contains("\"replaceCollections\":false"),
        )
    }

    @Test
    fun `a stage this phone HAS read claims the sweep on the wire`() {
        val body = encode(seen = true)
        assertTrue("a read stage may say 'these are exactly the rows': $body", body.contains("\"replaceCollections\":true"))
    }

    @Test
    fun `merge is still absent when false, which is the opposite rule and also load-bearing`() {
        // A read stage sends no `merge`, and must not start sending one: `APIModel` is `extra="forbid"`
        // on an API that predates the field, so `"merge":false` would 422 every save. This is what
        // stops `encodeDefaults = true` being adopted as the fix for the test above.
        val body = encode(seen = true)
        assertFalse("no `merge` key at all on a replace save: $body", body.contains("\"merge\""))
        // And it IS sent when true, or the never-read save stops preserving the office's keys.
        assertTrue(encode(seen = false).contains("\"merge\":true"))
    }

    @Test
    fun `the flag the server would read back matches the authority the builder decided`() {
        // The round trip, so the claim is about what the SERVER sees rather than about a substring.
        // `entries` is dropped from the comparison: it is the payload, not the claim.
        for (seen in listOf(false, true)) {
            val decoded = wire.decodeFromString(StageSaveBody.serializer(), encode(seen))
            assertEquals("authority in, authority out", seen, decoded.replaceCollections)
        }
    }

    @Test
    fun `an emptied collection is named only under a claim the server will honour`() {
        // `emptiedEntities` is read by the server ONLY when `replaceCollections` is true, so naming it
        // without the flag is a deletion asserted while the knowledge that justifies one is disclaimed
        // — and, with the flag omitted rather than sent as false, it was a deletion the server DID act
        // on. Both halves are asserted on the JSON, in one place, because they only mean anything
        // together.
        val unread = encode(seen = false, emptied = listOf("tool"))
        assertTrue(unread.contains("\"replaceCollections\":false"))
        assertFalse("nothing is named for a sweep that is not claimed: $unread", unread.contains("\"emptiedEntities\""))

        val read = encode(seen = true, emptied = listOf("tool"))
        assertTrue(read.contains("\"replaceCollections\":true"))
        assertTrue("the designer's deletion travels: $read", read.contains("\"emptiedEntities\":[\"tool\"]"))
    }
}
