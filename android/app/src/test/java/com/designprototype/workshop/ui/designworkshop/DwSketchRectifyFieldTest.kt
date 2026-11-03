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

class DwSketchRectifyFieldTest {

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

    @Test
    fun `the offer lands on the sketch's line-art file and nowhere else on that entity`() {
        val siblings = siblingsOf("SKETCH_DEVELOPMENT", "sketch")
        val offered = siblings.values.filter { dwOffersSketchRectify(it, siblings) }.map { it.key }
        assertEquals(listOf("lineArtFile"), offered)

        assertFalse(dwOffersSketchRectify(siblings.getValue("image"), siblings))
    }

    @Test
    fun `the sketch's own photograph is the source`() {
        val siblings = siblingsOf("SKETCH_DEVELOPMENT", "sketch")
        assertEquals(listOf("image"), dwSketchSourceFields(siblings).map { it.key })
    }

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

    @Test
    fun `every image field of a qualifying entity is a possible source`() {
        assertEquals(
            listOf("finalPhotos", "catalogPhotos", "turntablePhotos"),
            dwSketchSourceFields(siblingsOf("FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct")).map { it.key },
        )
    }

    @Test
    fun `a plate destination with no photograph on the entity is not offered`() {
        val siblings = listOf(
            FieldDto(key = "lineArtFile", label = "Line art / vector file", type = "FILE"),
            FieldDto(key = "note", label = "Note", type = "LONG_TEXT"),
        ).associateBy { it.key }
        assertTrue(dwSketchSourceFields(siblings).isEmpty())
        assertFalse(dwOffersSketchRectify(siblings.getValue("lineArtFile"), siblings))
    }

    @Test
    fun `the destination is recognised from either the key or the label`() {
        val photo = FieldDto(key = "image", label = "Sketch image", type = "IMAGE")
        fun offered(field: FieldDto): Boolean =
            dwOffersSketchRectify(field, listOf(photo, field).associateBy { it.key })

        assertTrue(offered(FieldDto(key = "lineArtFile", label = "Attachment", type = "FILE")))
        assertTrue(offered(FieldDto(key = "f12", label = "Line art / vector file", type = "FILE")))
        assertTrue(offered(FieldDto(key = "f13", label = "Vector export", type = "FILE")))
        assertTrue(offered(FieldDto(key = "artworkFile", label = "Artwork", type = "FILE")))

        assertFalse(offered(FieldDto(key = "consentForm", label = "Signed consent", type = "FILE")))
        assertFalse(offered(FieldDto(key = "invoice", label = "Invoice PDF", type = "FILE")))

        assertFalse(offered(FieldDto(key = "lineArtFile", label = "Line art", type = "IMAGE")))
        assertFalse(
            offered(FieldDto(key = "lineArtFile", label = "Line art", type = "FILE", deprecated = true))
        )
    }
}
