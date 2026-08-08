package com.designprototype.workshop.ui.designworkshop

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
 * What "straighten a photographed sketch into a plate" is OFFERED ON, pinned against the bundled
 * registry.
 *
 * ── WHY THIS TEST EXISTS AT ALL ───────────────────────────────────────────────────────────────
 *
 * `DwSketchRectifyTest` proves the arithmetic against the same constructions the web spec uses. What
 * it cannot prove — and what this repository has now got wrong repeatedly, including four whole
 * modules R8 deleted from a release APK because nothing reached them — is that anything REACHES it.
 * The panel is composed only where [dwOffersSketchRectify] returns true, and that function is three
 * declaration lookups: a renamed field, a changed type token or a label reworded past the regex does
 * not raise, does not warn and does not log. It returns false, the panel is not composed, and a
 * feature that took a week is invisible on the one screen it was written for.
 *
 * IT PINS THE SHIPPED ASSET AND NOT THE LIVE SERVER, for the same reason [DwPhotoMeasureFieldTest]
 * does: `design-workshop-schema.json` is what a handset renders from before it has ever had a
 * connection, so it is the copy that decides what a courtyard sees.
 *
 * ── AND WHY THE REFUSALS ARE TESTED AS HARD AS THE OFFER ──────────────────────────────────────
 *
 * The panel writes into the field it is drawn under. Offered on the wrong field it would be a control
 * that puts a PNG where the registry expects something else — and offered on `sketch.image` it would
 * be a control that DETACHES the photograph, because a single-valued IMAGE field replaces its value
 * when something is attached to it. That is the outcome docs/MEDIA_PIPELINE.md §5 exists to forbid,
 * so "not offered on the image field" is not a tidiness assertion; it is the safety property.
 */
class DwSketchRectifyFieldTest {

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
     * Stage 11's sketch is the pairing this was written for, and the offer lands on `lineArtFile`.
     *
     * The entity declares `image` — required, the photograph of the sheet — and `lineArtFile`, "An
     * SVG or vector export, if one was produced". A photograph to read and a separate slot to put the
     * derived plate in. If either key is renamed, this fails here rather than in a courtyard.
     */
    @Test
    fun `the offer lands on the sketch's line-art file and nowhere else on that entity`() {
        val siblings = siblingsOf("SKETCH_DEVELOPMENT", "sketch")
        val offered = siblings.values.filter { dwOffersSketchRectify(it, siblings) }.map { it.key }
        assertEquals(listOf("lineArtFile"), offered)

        // THE SAFETY PROPERTY. Offered on `image`, the panel would attach the plate to a
        // single-valued IMAGE field, which REPLACES its value — detaching the photograph that is the
        // record. See the class header and docs/MEDIA_PIPELINE.md §5.
        assertFalse(dwOffersSketchRectify(siblings.getValue("image"), siblings))
    }

    /** The photograph the plate is made from comes from the entity's image fields, in form order. */
    @Test
    fun `the sketch's own photograph is the source`() {
        val siblings = siblingsOf("SKETCH_DEVELOPMENT", "sketch")
        assertEquals(listOf("image"), dwSketchSourceFields(siblings).map { it.key })
    }

    /**
     * EXACTLY TWO FIELDS IN THE WHOLE REGISTRY GET THE OFFER, and both are named here rather than
     * counted, because the value of this test is that a change to the list has to be argued for.
     *
     * Stage 11's `sketch.lineArtFile` is the case the feature was written for. Stage 16's
     * `finalProduct.lineDrawing` ("Vector / line drawing") is the second, and it looks at first like a
     * false positive: a line drawing for a finished product is often a CAD export this panel has
     * nothing to do with. It is kept because it is just as often a technical drawing made on paper and
     * photographed, and because the web's `offersSketchRectify` reaches the same two places from the
     * same regex — two clients offering a feature in different places is worse than either choice.
     *
     * The other six FILE fields in the registry are refused, and the refusals are the load-bearing
     * half: `sanctionDocument`, `questionnaireFile`, `measurementSheet`, `modelFile`,
     * `certificateFile` and `checksumManifest` are all places a PNG of a straightened sheet has no
     * business being written.
     */
    @Test
    fun `exactly two fields in the bundled registry offer it`() {
        val found = mutableListOf<String>()
        for (stage in schema.stages) {
            for (candidateEntity in stage.entities) {
                val siblings = candidateEntity.liveFields.associateBy { it.key }
                for (candidate in siblings.values) {
                    if (dwOffersSketchRectify(candidate, siblings)) {
                        found += "${stage.key}.${candidateEntity.key}.${candidate.key}"
                    }
                }
            }
        }
        assertEquals(
            listOf(
                "SKETCH_DEVELOPMENT.sketch.lineArtFile",
                "FINAL_PROTOTYPE_DOCUMENTATION.finalProduct.lineDrawing",
            ),
            found,
        )
    }

    /**
     * Every image field of a qualifying entity is offered as a source, in the order the form renders
     * them — three of them on stage 16's final product.
     *
     * NOTHING TRIES TO PICK ONE, for the same reason [dwOffersPhotoMeasure] does not: which photograph
     * is of a sheet of paper and which is of the object is a fact about what somebody pointed a camera
     * at, and a designer choosing from a list of their own photographs beats any guess this file could
     * make.
     */
    @Test
    fun `every image field of a qualifying entity is a possible source`() {
        assertEquals(
            listOf("finalPhotos", "catalogPhotos", "turntablePhotos"),
            dwSketchSourceFields(siblingsOf("FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct")).map { it.key },
        )
    }

    /**
     * A file field that looks like a plate destination but has no photograph to make one FROM gets
     * nothing.
     *
     * This is the second of the two conditions and the one that matters: the offer is a promise that
     * something can be produced, and a straightening panel over an entity with no image is a promise
     * with no input.
     */
    @Test
    fun `a plate destination with no photograph on the entity is not offered`() {
        val siblings = listOf(
            FieldDto(key = "lineArtFile", label = "Line art / vector file", type = "FILE"),
            FieldDto(key = "note", label = "Note", type = "LONG_TEXT"),
        ).associateBy { it.key }
        assertTrue(dwSketchSourceFields(siblings).isEmpty())
        assertFalse(dwOffersSketchRectify(siblings.getValue("lineArtFile"), siblings))
    }

    /**
     * The name test reads the key AND the label, folded to letters.
     *
     * `lineArtFile` and "Line art / vector file" both have to match, because a registry is free to
     * express the same thing either way — and a field whose key is opaque but whose label says
     * "vector plate" is exactly the case a key-only test would miss.
     */
    @Test
    fun `the destination is recognised from either the key or the label`() {
        val photo = FieldDto(key = "image", label = "Sketch image", type = "IMAGE")
        fun offered(field: FieldDto): Boolean =
            dwOffersSketchRectify(field, listOf(photo, field).associateBy { it.key })

        assertTrue(offered(FieldDto(key = "lineArtFile", label = "Attachment", type = "FILE")))
        assertTrue(offered(FieldDto(key = "f12", label = "Line art / vector file", type = "FILE")))
        assertTrue(offered(FieldDto(key = "f13", label = "Vector export", type = "FILE")))
        assertTrue(offered(FieldDto(key = "artworkFile", label = "Artwork", type = "FILE")))

        // A file field that is plainly something else. A false positive here is cheap — a panel
        // nobody presses — but it is still a control offering to put a PNG somewhere it does not go.
        assertFalse(offered(FieldDto(key = "consentForm", label = "Signed consent", type = "FILE")))
        assertFalse(offered(FieldDto(key = "invoice", label = "Invoice PDF", type = "FILE")))

        // Right name, wrong type: an IMAGE field called "line art" is still a field that replaces its
        // own value, so it is refused on the type before the name is ever consulted.
        assertFalse(offered(FieldDto(key = "lineArtFile", label = "Line art", type = "IMAGE")))
        // A retired field is never a destination, whatever it is called.
        assertFalse(
            offered(FieldDto(key = "lineArtFile", label = "Line art", type = "FILE", deprecated = true))
        )
    }
}
