package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwPhotoMeasure
import com.designprototype.workshop.data.DwRoundedValue
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.entity
import com.designprototype.workshop.data.liveFields
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What "measure a dimension from a photograph" is OFFERED ON, pinned against the bundled registry.
 *
 * ── WHY THIS TEST AND NOT A SCREENSHOT ────────────────────────────────────────────────────────
 *
 * `DwPhotoMeasureTest` already proves the arithmetic, value for value, against the same constructions
 * the web's `e2e/photo-measure.spec.ts` uses. What it cannot prove — and what this repository has now
 * got wrong five times — is that anything REACHES it. The panel is reachable only where
 * [dwOffersPhotoMeasure] returns true, and that function is a pair of declaration lookups: a wrong
 * type token or a unit spelled differently in the registry does not raise, does not warn and does not
 * log. It returns false, the panel is not composed, and the feature is invisible on exactly the
 * screens it was written for — which reads, to the designer holding the phone, as a feature that does
 * not exist.
 *
 * IT PINS THE SHIPPED ASSET AND NOT THE LIVE SERVER, for the same reason `DwFindingsSurfaceTest`
 * does: `design-workshop-schema.json` is what a handset renders from before it has ever had a
 * connection, so it is the copy that decides what a courtyard sees.
 *
 * ── AND WHY IT ALSO CHECKS WHAT IS *NOT* OFFERED ──────────────────────────────────────────────
 *
 * A measurement proposed into a weight in grams, or into a price, is not a smaller version of the
 * right answer. It is a plausible number in a field nobody can re-check, multiplied into a cost sheet
 * — the exact failure the whole feature exists to reduce. So the refusals are tested as hard as the
 * offers.
 */
class DwPhotoMeasureFieldTest {

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private fun entityOf(stageKey: String, entityKey: String): EntityDto =
        schema.stages.firstOrNull { it.key == stageKey }?.entity(entityKey)
            ?: throw AssertionError("the registry declares no `$stageKey`.`$entityKey`")

    private fun siblingsOf(stageKey: String, entityKey: String): Map<String, FieldDto> =
        entityOf(stageKey, entityKey).liveFields.associateBy { it.key }

    /**
     * The four entities the offer is meant to appear on, and the dimensions it proposes into.
     *
     * These are exactly the registry's entities that describe a physical object somebody photographs.
     * A rename of `lengthCm` or a change of its declared unit takes the offer away silently, so the
     * keys are named here rather than counted.
     */
    @Test
    fun `every entity that describes a photographed object offers the measurement`() {
        val expected = mapOf(
            ("EXISTING_PRODUCTS_BASELINE" to "existingProduct") to listOf("lengthCm", "widthCm", "heightCm"),
            ("SKETCH_DEVELOPMENT" to "sketch") to listOf("lengthCm", "widthCm", "heightCm"),
            ("PROTOTYPE_DEVELOPMENT" to "prototype") to
                listOf("lengthCm", "widthCm", "heightCm", "diameterCm"),
            ("FINAL_PROTOTYPE_DOCUMENTATION" to "finalProduct") to listOf("lengthCm", "widthCm", "heightCm"),
        )
        for ((location, keys) in expected) {
            val (stageKey, entityKey) = location
            val siblings = siblingsOf(stageKey, entityKey)
            assertEquals(
                "`$stageKey`.`$entityKey` no longer proposes into the dimensions the registry declares",
                keys,
                dwMeasurableLengthFields(siblings).map { it.field.key },
            )
        }
    }

    /**
     * The offer is made on EVERY image field of a qualifying entity, and on none of its other fields.
     *
     * Stage 13's prototype declares two — `prototypePhotos` and `turntablePhotos` — and both get it,
     * because nothing in this app can tell which photograph the designer laid a ruler beside. That is
     * the whole argument in [dwOffersPhotoMeasure], and it is the part a "tidy-up" would undo.
     */
    @Test
    fun `the offer lands on the image fields and nowhere else`() {
        val siblings = siblingsOf("PROTOTYPE_DEVELOPMENT", "prototype")
        val offered = siblings.values.filter { dwOffersPhotoMeasure(it, siblings) }.map { it.key }
        assertEquals(listOf("prototypePhotos", "turntablePhotos"), offered)

        // The dimension fields themselves must not offer it: a panel under `lengthCm` would be a
        // measuring surface with no photograph on it.
        assertFalse(dwOffersPhotoMeasure(siblings.getValue("lengthCm"), siblings))
    }

    /** An entity with photographs but no length field gets no offer — there is nowhere to propose into. */
    @Test
    fun `photographs alone are not enough`() {
        val siblings = mapOf(
            "photos" to FieldDto(key = "photos", label = "Photographs", type = "IMAGE_LIST"),
            "note" to FieldDto(key = "note", label = "Note", type = "LONG_TEXT"),
        )
        assertTrue(dwMeasurableLengthFields(siblings).isEmpty())
        assertFalse(dwOffersPhotoMeasure(siblings.getValue("photos"), siblings))
    }

    /**
     * Everything a photograph cannot measure is refused as a destination.
     *
     * A photograph cannot weigh anything, cannot count days and cannot price a pot. Each of these
     * would have accepted a centimetre figure and printed it, in a field that is multiplied into a
     * cost sheet by somebody who was not in the room.
     */
    @Test
    fun `weights prices counts and unknown units are never destinations`() {
        val siblings = listOf(
            FieldDto(key = "weightG", label = "Weight", type = "DECIMAL", unit = "g"),
            FieldDto(key = "durationDays", label = "Duration", type = "INT", unit = "days"),
            FieldDto(key = "price", label = "Price", type = "MONEY", unit = "cm"),
            FieldDto(key = "share", label = "Share", type = "PERCENT", unit = "cm"),
            FieldDto(key = "span", label = "Span", type = "DECIMAL", unit = "hands"),
            FieldDto(key = "retired", label = "Old length", type = "DECIMAL", unit = "cm", deprecated = true),
            FieldDto(key = "caption", label = "Caption", type = "TEXT", unit = "cm"),
        ).associateBy { it.key }
        assertEquals(emptyList<String>(), dwMeasurableLengthFields(siblings).map { it.field.key })
    }

    /** A declared unit in another case, or with a stray space, is still a length. */
    @Test
    fun `the declared unit is read case-folded and trimmed`() {
        val siblings = listOf(
            FieldDto(key = "a", label = "A", type = "DECIMAL", unit = " CM "),
            FieldDto(key = "b", label = "B", type = "INT", unit = "MM"),
        ).associateBy { it.key }
        assertEquals(listOf("cm", "mm"), dwMeasurableLengthFields(siblings).map { it.unit })
    }

    /**
     * ONE MAP. Every unit this offers as a destination must be one [DwPhotoMeasure] can convert into.
     *
     * The two are wired to the same map on purpose, so this can only fail if somebody introduces a
     * second opinion about what a length is — which is how a field would come to be offered as a
     * destination that the propose button then refuses to convert into, leaving a designer looking at
     * a control that does nothing.
     */
    @Test
    fun `every offered destination is a unit the module can convert`() {
        val entities = listOf(
            "EXISTING_PRODUCTS_BASELINE" to "existingProduct",
            "SKETCH_DEVELOPMENT" to "sketch",
            "PROTOTYPE_DEVELOPMENT" to "prototype",
            "FINAL_PROTOTYPE_DOCUMENTATION" to "finalProduct",
        )
        for ((stageKey, entityKey) in entities) {
            for (target in dwMeasurableLengthFields(siblingsOf(stageKey, entityKey))) {
                assertTrue(
                    "`${target.field.key}` is offered in ${target.unit}, which the module cannot convert",
                    DwPhotoMeasure.LENGTH_UNITS.containsKey(target.unit)
                )
                assertEquals(
                    1.0,
                    DwPhotoMeasure.convertLength(1.0, target.unit, target.unit) ?: 0.0,
                    0.0,
                )
            }
        }
    }

    /**
     * The proposal is rendered at exactly the precision its error bar reached, and NOT one digit more.
     *
     * The rounding happens once, in [DwPhotoMeasure.roundToUncertainty], through its port of
     * JavaScript's `Math.round`; [dwFormatRounded] only writes the digits out. A formatter that did
     * its own rounding would put the handset and the browser one unit apart on every exact binary tie
     * — which is the divergence that module's header is entirely about.
     */
    @Test
    fun `a proposal is written to the decimals its error bar supports`() {
        assertEquals("20.0", dwFormatRounded(DwRoundedValue(20.0, 1)))
        assertEquals("12", dwFormatRounded(DwRoundedValue(12.0, 0)))
        assertEquals("0.0335", dwFormatRounded(DwRoundedValue(0.0335, 4)))
        // What the module actually produces for a 19.98471 cm reading with a 0.3 cm bar: one decimal.
        val rounded = DwPhotoMeasure.roundToUncertainty(19.98471, 0.3)
        assertEquals(1, rounded.decimals)
        assertEquals("20.0", dwFormatRounded(rounded))
    }
}
