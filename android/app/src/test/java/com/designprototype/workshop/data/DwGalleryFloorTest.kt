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

/**
 * **"ALL 25 ARE REQUIRED"** — where that is enforced on this client, and, just as importantly, where
 * it is not.
 *
 * The owner's instruction of 2026-08-27 gave both motif galleries a count of twenty-five with every
 * one of them required. The server carries that through `min_items`, which is SCORED in
 * `stage_completeness` and validated nowhere at all — and this file's first job is to hold that
 * asymmetry on the handset, because the handset is the reason for it: `saveOrQueue` DROPS a 4xx
 * rather than queueing it, so a floor that could refuse a write would not delay a village day's work,
 * it would destroy it, on stage 4 of a twenty-two-stage flow.
 *
 * The second job is the label. `missing` is printed verbatim on the readiness screen, in the report's
 * warning line and in the completeness annexure, and every OTHER entry in it means "nothing was
 * recorded" — so a bare "Traditional motif photographs" about a gallery holding twenty-four
 * photographs tells a designer the app has lost them.
 */
class DwGalleryFloorTest {

    private val workshopId = "cmfloorworkshop0000000001"

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
    private val assetJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Stage 4 in miniature, with the registry's real shape: both galleries STANDARD tier, NOT
     * flagged `required`, and floored at twenty-five.
     *
     * THE TIER AND THE FLAG ARE THE POINT OF THE FIXTURE. If a floor only counted on a BASIC field,
     * or only on one already marked required, the whole feature would score nothing here — and that
     * is exactly what these two fields look like in the bundled registry.
     */
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
                            // Floors nothing: an ordinary optional gallery, which must keep counting
                            // as optional however full or empty it is.
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

    // ── The floor is scored ─────────────────────────────────────────────────────────────────────

    /**
     * A DECLARED FLOOR MAKES THE FIELD REQUIRED AT WHATEVER TIER IT SITS AT — a deliberate exception
     * to "only BASIC may be required", taken so the two galleries did not have to be promoted to
     * BASIC to be counted. Promotion would have spliced fifty photographs into the one report
     * template whose whole description is "Basic-tier fields only".
     */
    @Test
    fun `a floored optional field counts as required`() {
        assertTrue(dwCountsAsRequired(motifPhotos))
        assertFalse(motifPhotos.required)
        // One BASIC required field plus the two floored galleries; the unfloored gallery stays
        // optional however it is filled.
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

    /**
     * AN EMPTY GALLERY STILL READS AS A SHORTFALL AND NOT AS A BARE LABEL, so the sentence a
     * designer meets is the same shape whether they have none or twenty-four. "(0 of 25)" also says
     * outright that the gallery is not merely un-started but has a target.
     */
    @Test
    fun `an untouched floored gallery reports zero of its floor`() {
        assertEquals(
            listOf("Traditional motif photographs (0 of 25)", "Contemporary motif photographs (0 of 25)"),
            score().missing,
        )
    }

    /**
     * A NON-LIST IS SHORT, NOT EXEMPT. A gallery holding one bare media id rather than a list is a
     * client bug, and answering "filled" for it would report a stage of one photograph as
     * twenty-five complete — the one wrong answer this predicate must never give.
     */
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
        // `clusterPhotos` is untouched and the stage is still complete.
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

    // ── The floor is NOT enforced on any write path ─────────────────────────────────────────────

    /**
     * THE ASSERTION THIS FILE EXISTS FOR.
     *
     * A designer in a village with twenty good photographs and no signal has no body that satisfies a
     * floor of twenty-five. [DwValues.validate] is what marks a stage's boxes red before it is sent,
     * and it is asked here with `enforceRequired = true` — the strictest arm there is — precisely
     * because a floored field IS counted as required by the scorer. It must still say nothing: on
     * this client a refused save is a DROPPED save rather than a retried one, so a floor that could
     * refuse a write would not delay that day's work, it would destroy it.
     */
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

    /**
     * AND THE CEILING ON THE SAME FIELD STILL REFUSES, because the two bounds are not symmetrical: a
     * designer holding twenty-six can always comply by posting twenty-five, while one holding twenty
     * has no body that satisfies a floor at all. `coerce_value` on the server refuses an over-long
     * array rather than trimming it, and [DwValues.coerceHydrated] is this client's copy of that
     * rule.
     */
    @Test
    fun `the ceiling on the same field is still enforced while the floor is not`() {
        assertEquals(null, DwValues.coerceHydrated(motifPhotos, gallery(26)))
        assertNotNull(DwValues.coerceHydrated(motifPhotos, gallery(20)))
    }

    // ── The readiness screen agrees with the scorer, decoration and all ─────────────────────────

    /**
     * THE ADDRESS WALK MATCHES ON THE SCORER'S EXACT STRING. Decorated in one place and not the
     * other, every motif item would degrade from "open the gallery" to "open the stage" — and the
     * designer would be dropped on a twenty-field form with nothing highlighted.
     */
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

    // ── And the registry this app actually ships agrees ─────────────────────────────────────────

    /**
     * THE FIXTURE ABOVE IS A MODEL; THIS IS THE REAL THING.
     *
     * `FieldDto.minItems` is a new key on the wire, and a client that decodes it as its default of
     * zero looks exactly like a client that read a registry with no floor in it — the bar would
     * simply not be drawn and the stage would score complete at one photograph, silently. The
     * bundled asset is the same document a fresh handset starts from, so it is worth one assertion
     * that the key survives the round trip with the value the owner asked for.
     */
    @Test
    fun `the bundled registry really does declare twenty-five on both motif galleries`() {
        // The SHIPPED asset and not the live server, for the reason `DwMediaCapCeilingTest` gives
        // about the same file: it is what a handset renders from before it has ever had a
        // connection, so it is the copy that decides what a courtyard is held to.
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
