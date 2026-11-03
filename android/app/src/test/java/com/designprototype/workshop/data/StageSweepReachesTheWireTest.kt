package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageSweepReachesTheWireTest {

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
        val body = encode(seen = true)
        assertFalse("no `merge` key at all on a replace save: $body", body.contains("\"merge\""))
        assertTrue(encode(seen = false).contains("\"merge\":true"))
    }

    @Test
    fun `the flag the server would read back matches the authority the builder decided`() {
        for (seen in listOf(false, true)) {
            val decoded = wire.decodeFromString(StageSaveBody.serializer(), encode(seen))
            assertEquals("authority in, authority out", seen, decoded.replaceCollections)
        }
    }

    @Test
    fun `an emptied collection is named only under a claim the server will honour`() {
        val unread = encode(seen = false, emptied = listOf("tool"))
        assertTrue(unread.contains("\"replaceCollections\":false"))
        assertFalse("nothing is named for a sweep that is not claimed: $unread", unread.contains("\"emptiedEntities\""))

        val read = encode(seen = true, emptied = listOf("tool"))
        assertTrue(read.contains("\"replaceCollections\":true"))
        assertTrue("the designer's deletion travels: $read", read.contains("\"emptiedEntities\":[\"tool\"]"))
    }
}
