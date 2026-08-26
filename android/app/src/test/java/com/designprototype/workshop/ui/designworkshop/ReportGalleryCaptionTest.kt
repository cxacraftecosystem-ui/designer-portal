package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.ReportDocument
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHERE A GALLERY'S NAME IS PRINTED — and, which is the half these pin, where it is printed TWICE.
 *
 * ── THE DEFECT THESE PIN ──────────────────────────────────────────────────────────────────────────
 *
 * `imagesOf` filled a blank cell caption with the owning FIELD'S LABEL, at gather time, for every
 * caller. That was written when there was one merged plate per record and no caption above it, so the
 * label under a cell was the only thing saying which field a picture came from. Once `imageGroupsOf`
 * started giving a multi-photograph field its OWN plate captioned by that same label, the fallback
 * printed the label again under every one of the plate's photographs: "Cluster photographs" once over
 * the grid and twenty more times beneath it, in a document submitted to a ministry.
 *
 * The office's copy has never done that. `report_builder.py:1392` is
 * `images.append((ref, caption or ("" if grid_caption else spec.label)))` — the fallback fires only on
 * the plate that has no caption of its own. So the two copies of one report disagreed, and the handset
 * was the wrong one.
 *
 * IT IS THE DEFAULT STATE AND NOT AN EDGE CASE. `photos()` (stage_definitions.py) declares every
 * gallery's `*Caption` box with no `required=True`, so blank is what a designer who did not type a
 * caption leaves behind — which is most of them — and stage 4 alone ships three galleries.
 *
 * ── WHAT IS ASSERTED, AND WHAT DELIBERATELY IS NOT ────────────────────────────────────────────────
 *
 * The assertions are on the CAPTIONS of one grid, never on the document's block count or ordering:
 * those belong to `ReportTemplateDocumentTest` and pinning them twice would make a legitimate template
 * change fail here for a reason this file is not about. The shared-plate test is the guard against
 * over-correcting — deleting the fallback outright would have passed the first test and stripped the
 * only field name off the mixed plate, where nothing else carries it.
 */
class ReportGalleryCaptionTest {

    // ── the registry, cut to stage 4's real shape ────────────────────────────────────────────────
    //
    // The keys, labels, tiers and roles are the ones `photos("clusterPhotos", "Cluster photographs",
    // S, "Cluster photograph caption")` and its `motifPhotos` sibling publish, because the pairing of
    // a gallery with a `*Caption` field through `captionFor` is exactly what is under test.

    private fun clusterSchema(vararg fields: FieldDto) = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 4, key = "CLUSTER_CRAFT_BACKGROUND", title = "Cluster & craft background",
                entities = listOf(
                    EntityDto(
                        key = "clusterBackground", cardinality = "SINGLETON",
                        title = "Cluster & craft background",
                        fields = listOf(
                            FieldDto(
                                key = "giDetails", label = "GI registration details", type = "TEXT",
                                reportRole = "KEY_VALUE", tier = "STANDARD",
                            ),
                        ) + fields.toList(),
                    )
                ),
            ),
            StageDto(
                number = 20, key = "REPORT_GENERATION", title = "Report generation",
                entities = listOf(
                    EntityDto(
                        key = "reportSettings", cardinality = "SINGLETON", title = "Report settings",
                        fields = listOf(
                            FieldDto(key = "reportTitle", label = "Report title", type = "TEXT"),
                        ),
                    )
                ),
            ),
        ),
    )

    private fun gallery(key: String, label: String) = FieldDto(
        key = key, label = label, type = "IMAGE_LIST", reportRole = "GALLERY", tier = "STANDARD",
    )

    private fun captionBox(key: String, label: String) = FieldDto(
        key = "${key}Caption", label = label, type = "TEXT", reportRole = "CAPTION",
        tier = "STANDARD", captionFor = key,
    )

    private fun draftOf(values: Map<String, JsonElement>) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            "CLUSTER_CRAFT_BACKGROUND" to StageDraft(
                stageId = "CLUSTER_CRAFT_BACKGROUND",
                values = mapOf("giDetails" to JsonPrimitive("GI/2013/0432")) + values,
            ),
        ),
    )

    private fun ids(vararg id: String) = buildJsonArray { id.forEach { add(JsonPrimitive(it)) } }

    /** Every token resolves, to a path that is NOT the token — the shape a real media cache answers. */
    private val resolvesEverything: (String) -> ImageRef? =
        { id -> ImageRef(source = "/data/media/$id.jpg", widthPx = 1600, heightPx = 1200) }

    private fun build(schema: SchemaResponse, draft: WorkshopDraft): ReportDocument =
        buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = "DCH_STANDARD",
            warnings = emptyList(),
            accent = "",
            imageFor = resolvesEverything,
            generatedAt = "2026-08-26T09:30:00Z",
        )

    // ── the named plate ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a named gallery prints its label over the grid and never again under a photograph`() {
        val document = build(
            clusterSchema(
                gallery("clusterPhotos", "Cluster photographs"),
                captionBox("clusterPhotos", "Cluster photograph caption"),
            ),
            // The caption box left blank, which is what `photos()` makes the default: it is optional
            // on every one of the registry's galleries.
            draftOf(mapOf("clusterPhotos" to ids("c1", "c2"))),
        )

        val grid = document.blocks.filterIsInstance<ImageGridBlock>().single()
        assertEquals(
            "the gallery's name belongs on the grid, which is the whole point of a named plate",
            "Cluster photographs", grid.caption,
        )
        assertEquals("both photographs must reach the plate", 2, grid.images.size)
        assertTrue(
            "the label printed under every photograph as well as over the grid, which the office's " +
                "copy of the same report has never done:\n${grid.images.map { it.second }}",
            grid.images.all { it.second.isEmpty() },
        )
    }

    @Test
    fun `a caption the designer typed survives on a named plate`() {
        val document = build(
            clusterSchema(
                gallery("clusterPhotos", "Cluster photographs"),
                captionBox("clusterPhotos", "Cluster photograph caption"),
            ),
            draftOf(
                mapOf(
                    "clusterPhotos" to ids("c1", "c2"),
                    "clusterPhotosCaption" to JsonPrimitive("Bandha weaving, Barpali"),
                )
            ),
        )

        val grid = document.blocks.filterIsInstance<ImageGridBlock>().single()
        assertEquals("Cluster photographs", grid.caption)
        assertEquals(
            "suppressing the LABEL must not suppress the sentence the designer wrote — which is why " +
                "this is a flag on the gather and not a comparison of the caption against the label",
            listOf("Bandha weaving, Barpali", "Bandha weaving, Barpali"),
            grid.images.map { it.second },
        )
    }

    // ── the shared plate, which is what the fallback was written for ──────────────────────────────

    @Test
    fun `the shared plate still names the field each photograph came from`() {
        val document = build(
            clusterSchema(
                gallery("clusterPhotos", "Cluster photographs"),
                captionBox("clusterPhotos", "Cluster photograph caption"),
                gallery("motifPhotos", "Traditional motif photographs"),
                captionBox("motifPhotos", "Traditional motif caption"),
            ),
            // ONE PHOTOGRAPH EACH, so neither field earns a plate of its own and the two share one —
            // the case `imageGroupsOf` leaves uncaptioned because no single name over it would be
            // true of both.
            draftOf(
                mapOf(
                    "clusterPhotos" to ids("c1"),
                    "motifPhotos" to ids("m1"),
                )
            ),
        )

        val grid = document.blocks.filterIsInstance<ImageGridBlock>().single()
        assertEquals(
            "a plate holding two fields' pictures must carry no name of its own", "", grid.caption,
        )
        assertEquals(
            "with no grid caption the cell label is the ONLY thing saying where a picture came " +
                "from, so this is the plate whose blank captions must still be filled in",
            listOf("Cluster photographs", "Traditional motif photographs"),
            grid.images.map { it.second },
        )
    }
}
