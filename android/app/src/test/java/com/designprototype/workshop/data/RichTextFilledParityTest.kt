package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextFilledParityTest {

    private fun doc(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private val blankNarrative = doc("""{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}""")

    @Test
    fun `a textually empty document is not an answer`() {
        assertFalse(DwValues.isFilled(blankNarrative))
        assertFalse(DwValues.isFilled(doc("""{"blocks":[]}""")))
        assertFalse(
            DwValues.isFilled(
                doc("""{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"   "}]},{"kind":"PARAGRAPH","spans":[]}]}""")
            )
        )
    }

    @Test
    fun `a document with words in it is an answer`() {
        assertTrue(
            DwValues.isFilled(
                doc("""{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"The dye vat is fired at dawn."}]}]}""")
            )
        )
    }

    @Test
    fun `an image-only document is an answer`() {
        assertTrue(DwValues.isFilled(doc("""{"blocks":[{"kind":"IMAGE","media":"m-1","spans":[]}]}""")))
        assertFalse(DwValues.isFilled(doc("""{"blocks":[{"kind":"IMAGE","media":"","spans":[]}]}""")))
    }

    @Test
    fun `a table with cells in it is an answer`() {
        assertTrue(
            DwValues.isFilled(
                doc("""{"blocks":[{"kind":"TABLE","rows":[[[{"text":"Warp"}],[{"text":"Cotton"}]]],"spans":[]}]}""")
            )
        )
    }

    @Test
    fun `a geo value is still an answer`() {
        assertTrue(DwValues.isFilled(DwValues.geoOf(26.9124, 75.7873)))
        assertTrue(DwValues.isFilled(doc("""{"lat":0,"lon":0}""")))
        assertFalse(DwValues.isFilled(doc("""{}""")))
    }

    @Test
    fun `a stage whose required narrative is blank is not complete`() {
        val stage = StageDto(
            number = 4,
            key = "context",
            title = "Cluster context",
            entities = listOf(
                EntityDto(
                    key = "context",
                    cardinality = "SINGLETON",
                    title = "Cluster context",
                    fields = listOf(
                        FieldDto(key = "clusterName", label = "Cluster", type = "TEXT", required = true),
                        FieldDto(key = "introduction", label = "Introduction", type = "RICH_TEXT", required = true),
                    ),
                )
            ),
        )
        val values: Map<String, JsonElement> = mapOf(
            "clusterName" to JsonPrimitive("Bagru"),
            "introduction" to blankNarrative,
        )
        val score = computeStageCompleteness(stage, values, emptyMap())
        assertEquals(2, score.requiredTotal)
        assertEquals(1, score.requiredFilled)
        assertEquals(50, score.percent)
        assertFalse(score.isComplete)
        assertTrue(score.missing.contains("Introduction"))
    }
}
