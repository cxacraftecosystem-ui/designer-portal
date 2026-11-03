package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwGalleryFloorTest {

    private val workshopId = "cmfloorworkshop0000000001"

    private val assetJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val registry = SchemaResponse(
        version = "floor-spec",
        stages = listOf(
            StageDto(
                number = 4, key = "CLUSTER_CRAFT_BACKGROUND", title = "Cluster, Area & Craft Background",
                entities = listOf(
                    EntityDto(
                        key = "clusterBackground", name = "DwClusterBackground", cardinality = "SINGLETON",
                        title = "Cluster & craft background",
                        fields = listOf(
                            FieldDto(
                                key = "clusterName", label = "Cluster name", type = "TEXT",
                                tier = "BASIC", required = true,
                            ),
                            FieldDto(
                                key = "motifPhotos", label = "Traditional motif photographs",
                                type = "IMAGE_LIST", tier = "STANDARD", required = false,
                                maxItems = 25, minItems = 25,
                            ),
                            FieldDto(
                                key = "contemporaryMotifPhotos", label = "Contemporary motif photographs",
                                type = "IMAGE_LIST", tier = "STANDARD", required = false,
                                maxItems = 25, minItems = 25,
                            ),
                            FieldDto(
                                key = "clusterPhotos", label = "Cluster photographs",
                                type = "IMAGE_LIST", tier = "STANDARD", required = false,
                            ),
                        ),
                    )
                ),
            ),
        ),
    )

    private val stage = registry.stages.single()
    private val motifPhotos = stage.singleton!!.fields.first { it.key == "motifPhotos" }

    private fun gallery(count: Int): JsonElement =
        buildJsonArray { repeat(count) { index -> add(JsonPrimitive("media-$index")) } }

    private fun score(vararg values: Pair<String, JsonElement>) = computeStageCompleteness(
        stage = stage,
        singleton = mapOf("clusterName" to JsonPrimitive("Bagru")) + values.toMap(),
        collections = emptyMap(),
    )

    @Test
    fun `a floored optional field counts as required`() {
        assertTrue(dwCountsAsRequired(motifPhotos))
        assertFalse(motifPhotos.required)
        assertEquals(3, score().requiredTotal)
    }

    @Test
    fun `a gallery one photograph short is not complete, and the shortfall carries its count`() {
        val short = score("motifPhotos" to gallery(24), "contemporaryMotifPhotos" to gallery(25))
        assertFalse(short.isComplete)
        assertEquals(2, short.requiredFilled)
        assertEquals(listOf("Traditional motif photographs (24 of 25)"), short.missing)
    }

    @Test
    fun `a gallery at its floor is complete`() {
        val met = score("motifPhotos" to gallery(25), "contemporaryMotifPhotos" to gallery(25))
        assertTrue(met.isComplete)
        assertEquals(100, met.percent)
        assertTrue(met.missing.isEmpty())
    }

    @Test
    fun `an untouched floored gallery reports zero of its floor`() {
        assertEquals(
            listOf("Traditional motif photographs (0 of 25)", "Contemporary motif photographs (0 of 25)"),
            score().missing,
        )
    }

    @Test
    fun `a bare media id where a list belongs is short rather than filled`() {
        val wrongShape = score("motifPhotos" to JsonPrimitive("media-0"))
        assertFalse(dwMeetsMinimum(motifPhotos, JsonPrimitive("media-0")))
        assertTrue(wrongShape.missing.contains("Traditional motif photographs (0 of 25)"))
    }

    @Test
    fun `a gallery with no declared floor never blocks, however empty`() {
        val filled = score(
            "motifPhotos" to gallery(25),
            "contemporaryMotifPhotos" to gallery(25),
        )
        assertTrue(filled.isComplete)
        assertFalse(filled.missing.any { it.startsWith("Cluster photographs") })
    }

    @Test
    fun `a declared floor of zero is no floor at all`() {
        val plain = stage.singleton!!.fields.first { it.key == "clusterPhotos" }
        assertEquals(0, plain.minItems)
        assertEquals(null, dwDeclaredMinItems(plain.minItems))
        assertFalse(dwCountsAsRequired(plain))
        assertTrue(dwMeetsMinimum(plain, gallery(0)))
        assertEquals("Cluster photographs", dwShortfallLabel(plain, gallery(0)))
    }

    @Test
    fun `a short gallery raises no validation error, even under enforceRequired`() {
        val entity = stage.singleton!!
        val errors = DwValues.validate(
            entity = entity,
            data = mapOf(
                "clusterName" to JsonPrimitive("Bagru"),
                "motifPhotos" to gallery(20),
            ),
            enforceRequired = true,
        )
        assertFalse("the floor must never reach the validator", errors.containsKey("motifPhotos"))
        assertFalse(errors.containsKey("contemporaryMotifPhotos"))
    }

    @Test
    fun `the ceiling on the same field is still enforced while the floor is not`() {
        assertEquals(null, DwValues.coerceHydrated(motifPhotos, gallery(26)))
        assertNotNull(DwValues.coerceHydrated(motifPhotos, gallery(20)))
    }

    @Test
    fun `a short gallery is a blocking item that links to the gallery itself`() {
        val draft = WorkshopDraft(
            workshopId = workshopId,
            stages = mapOf(
                "CLUSTER_CRAFT_BACKGROUND" to StageDraft(
                    stageId = "CLUSTER_CRAFT_BACKGROUND",
                    values = mapOf(
                        "clusterName" to JsonPrimitive("Bagru"),
                        "motifPhotos" to gallery(20),
                        "contemporaryMotifPhotos" to gallery(25),
                    ),
                )
            ),
        )
        val readiness = DwSubmissionReadiness.assess(registry, draft, workshopId)
        val item = readiness.blocking.firstOrNull { it.label == "Traditional motif photographs (20 of 25)" }
        assertNotNull("the short gallery is listed under the scorer's own label", item)
        assertEquals("motifPhotos", item?.address?.fieldKey)
        assertEquals("clusterBackground", item?.address?.entityKey)
        assertFalse(readiness.isSubmittable)
    }

    @Test
    fun `a gallery at its floor produces no readiness item at all`() {
        val draft = WorkshopDraft(
            workshopId = workshopId,
            stages = mapOf(
                "CLUSTER_CRAFT_BACKGROUND" to StageDraft(
                    stageId = "CLUSTER_CRAFT_BACKGROUND",
                    values = mapOf(
                        "clusterName" to JsonPrimitive("Bagru"),
                        "motifPhotos" to gallery(25),
                        "contemporaryMotifPhotos" to gallery(25),
                    ),
                )
            ),
        )
        val readiness = DwSubmissionReadiness.assess(registry, draft, workshopId)
        assertTrue(readiness.blocking.none { it.label.startsWith("Traditional motif photographs") })
    }

    @Test
    fun `the bundled registry really does declare twenty-five on both motif galleries`() {
        val asset = java.io.File("src/main/assets/design-workshop-schema.json")
        assertTrue("the bundled registry is missing", asset.exists())
        val bundled = assetJson.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
        val background = bundled.stages
            .firstOrNull { it.key == "CLUSTER_CRAFT_BACKGROUND" }
            ?.singleton
        assertNotNull("stage 4 has a singleton entity", background)
        for (key in listOf("motifPhotos", "contemporaryMotifPhotos")) {
            val field = background!!.fields.firstOrNull { it.key == key }
            assertNotNull("$key is declared", field)
            assertEquals("$key is floored at 25", 25, field!!.minItems)
            assertEquals("$key is capped at 25", 25, field.maxItems)
            assertTrue("$key counts as required", dwCountsAsRequired(field))
        }
    }
}
