package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A narrative with no words in it is not an answer — on the phone as on the server and the browser.
 *
 * `DwValues.isFilled` ended at `is JsonObject -> value.isNotEmpty()` and had no arm for rich text, so
 * `{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}` — a document an editor that was opened and left
 * alone produces — scored as a filled required field. `_is_filled` (stage_schema.py) and the web's
 * `isFilled` (designWorkshops.ts) both branch on the `blocks` key and read the TEXT. The phone was
 * therefore the optimistic one: its stage index called a stage complete while the same workshop on a
 * laptop listed the introduction as outstanding, and the phone is the surface a designer uses to
 * decide the fieldwork is done and leave the cluster.
 *
 * The reachability is narrow on purpose and worth stating so nobody deletes this as unreachable: the
 * server's RICH_TEXT coercion normalises an empty document to null on the way in, and the handset's
 * editor emits null for one, so a value in this shape arrives from an older build or a direct API
 * caller. The score being wrong for a value the current clients cannot write is still the score being
 * wrong, and the fix costs one branch.
 *
 * The predicate has to be the report model's own `isEmptyDocument`, not a second opinion: the scorer
 * and the document the scorer is about must not disagree over the same value.
 */
class RichTextFilledParityTest {

    private fun doc(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    /** The exact shape an editor that was focused and left alone leaves behind. */
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

    /**
     * A narrative whose only content is a photograph counts, with no caption.
     *
     * This is why the arm delegates to `isEmptyDocument` rather than asking "is there any text": the
     * report model's `RichBlock.isEmpty` already makes an IMAGE non-empty while it holds a media id,
     * and a field whose only content is a picture reading as unfilled would be the same defect
     * pointing the other way.
     */
    @Test
    fun `an image-only document is an answer`() {
        assertTrue(DwValues.isFilled(doc("""{"blocks":[{"kind":"IMAGE","media":"m-1","spans":[]}]}""")))
        assertFalse(DwValues.isFilled(doc("""{"blocks":[{"kind":"IMAGE","media":"","spans":[]}]}""")))
    }

    /**
     * A filled table counts. `RichBlock.text` is defined for TABLE for exactly this reason, and this
     * row exists so the delegation cannot be "simplified" into a spans-only text test.
     */
    @Test
    fun `a table with cells in it is an answer`() {
        assertTrue(
            DwValues.isFilled(
                doc("""{"blocks":[{"kind":"TABLE","rows":[[[{"text":"Warp"}],[{"text":"Cotton"}]]],"spans":[]}]}""")
            )
        )
    }

    /**
     * EVERY OTHER OBJECT IS UNTOUCHED, and this is the assertion that guards the `blocks` key test.
     *
     * `isEmptyDocument` parses an object with no `blocks` array as a document with no blocks — i.e.
     * as empty — so widening the new arm to all JsonObjects would stop counting every recorded
     * coordinate and every custom-answer container on the device.
     */
    @Test
    fun `a geo value is still an answer`() {
        assertTrue(DwValues.isFilled(DwValues.geoOf(26.9124, 75.7873)))
        assertTrue(DwValues.isFilled(doc("""{"lat":0,"lon":0}""")))
        assertFalse(DwValues.isFilled(doc("""{}""")))
    }

    /**
     * And the score the designer actually reads.
     *
     * Two required fields, one narrative left blank: the stage must read 50%, not 100%. This is the
     * end-to-end assertion — `computeStageCompleteness` calls `isFilled` for the singleton, the
     * custom container and every collection row through the one predicate.
     */
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
        // And it must be NAMED, not merely counted — the readiness screen shows this list.
        assertTrue(score.missing.contains("Introduction"))
    }
}
