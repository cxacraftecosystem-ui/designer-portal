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

class ReportGalleryCaptionTest {

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

    @Test
    fun `a named gallery prints its label over the grid and never again under a photograph`() {
        val document = build(
            clusterSchema(
                gallery("clusterPhotos", "Cluster photographs"),
                captionBox("clusterPhotos", "Cluster photograph caption"),
            ),
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

    @Test
    fun `the shared plate still names the field each photograph came from`() {
        val document = build(
            clusterSchema(
                gallery("clusterPhotos", "Cluster photographs"),
                captionBox("clusterPhotos", "Cluster photograph caption"),
                gallery("motifPhotos", "Traditional motif photographs"),
                captionBox("motifPhotos", "Traditional motif caption"),
            ),
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
