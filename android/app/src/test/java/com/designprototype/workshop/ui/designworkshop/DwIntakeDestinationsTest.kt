package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwStageData
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwIntakeDestinationsTest {

    private val registry = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 1,
                key = "WORKSHOP_IDENTIFICATION",
                title = "Workshop Identification",
                entities = listOf(
                    EntityDto(
                        key = "workshop",
                        name = "DwWorkshop",
                        cardinality = "SINGLETON",
                        title = "Workshop",
                        fields = listOf(
                            FieldDto(key = "startDate", label = "Start date", type = "DATE", tier = "BASIC"),
                            FieldDto(key = "coverPhoto", label = "Cover photograph", type = "IMAGE"),
                        ),
                    ),
                    EntityDto(
                        key = "notes",
                        name = "DwNote",
                        cardinality = "SINGLETON",
                        title = "Notes",
                        fields = listOf(FieldDto(key = "summary", label = "Summary", type = "LONG_TEXT")),
                    ),
                ),
            ),
            StageDto(
                number = 13,
                key = "PROTOTYPE_DEVELOPMENT",
                title = "Prototype Development",
                entities = listOf(
                    EntityDto(
                        key = "prototypeStageLog",
                        name = "DwPrototypeStageLog",
                        cardinality = "COLLECTION",
                        title = "Stage logs",
                        labelField = "activity",
                        fields = listOf(
                            FieldDto(key = "logDate", label = "Date", type = "DATE"),
                            FieldDto(key = "activity", label = "Activity", type = "TEXT"),
                            FieldDto(key = "logPhotos", label = "Photographs", type = "IMAGE_LIST"),
                            FieldDto(key = "closeUp", label = "Close-up", type = "IMAGE"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    private fun logs(vararg rows: Map<String, JsonElement>): Map<String, DwStageData> = mapOf(
        "PROTOTYPE_DEVELOPMENT" to DwStageData(collections = mapOf("prototypeStageLog" to rows.toList())),
    )

    private val cover = "WORKSHOP_IDENTIFICATION|workshop||coverPhoto"

    @Test
    fun `a singleton offers its image field even when this device holds no data for the stage`() {
        val out = dwIntakeDestinations(registry, emptyMap())

        assertEquals(listOf(cover), out.map { it.key })
        assertEquals("1. Workshop Identification — Cover photograph", out[0].label)
        assertNull(out[0].rowKey)
        assertFalse(out[0].multiple)
        assertEquals(1, out[0].stageNumber)
        assertEquals("coverPhoto", out[0].fieldKey)
    }

    @Test
    fun `a collection contributes one destination per row per image field, in declaration order`() {
        val out = dwIntakeDestinations(
            registry,
            logs(
                mapOf("_clientKey" to text("row-a"), "activity" to text("Warping the loom")),
                mapOf("_entryId" to text("row-b"), "activity" to text("Dyeing the weft")),
            ),
        )

        assertEquals(
            listOf(
                cover,
                "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-a|logPhotos",
                "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-a|closeUp",
                "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-b|logPhotos",
                "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-b|closeUp",
            ),
            out.map { it.key },
        )
        assertEquals(
            "13. Prototype Development — Stage logs “Warping the loom” — Photographs",
            out[1].label,
        )
        assertTrue(out[1].multiple)
        assertFalse(out[2].multiple)
        assertEquals("row-a", out[1].rowKey)
    }

    @Test
    fun `_clientKey addresses the row, and a null one falls through to _entryId`() {
        val out = dwIntakeDestinations(
            registry,
            logs(
                mapOf(
                    "_clientKey" to text("client-a"),
                    "_entryId" to text("server-a"),
                    "activity" to text("Both keys"),
                ),
                mapOf("_clientKey" to JsonNull, "_entryId" to text("server-b"), "activity" to text("Null key")),
            ),
        )

        assertEquals(listOf("client-a", "client-a", "server-b", "server-b"), out.drop(1).map { it.rowKey })
    }

    @Test
    fun `a row with no key at all contributes no destination, and the ordinals still count it`() {
        val out = dwIntakeDestinations(
            registry,
            logs(
                mapOf("activity" to text("Keyless")),
                mapOf("_clientKey" to text("row-b")),
            ),
        )

        assertEquals(
            listOf(cover, "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-b|logPhotos", "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-b|closeUp"),
            out.map { it.key },
        )
        assertEquals("13. Prototype Development — Stage logs “row 2” — Photographs", out[1].label)
    }

    @Test
    fun `a row whose key is the empty string contributes no destination either`() {
        val out = dwIntakeDestinations(
            registry,
            logs(
                mapOf("_clientKey" to text(""), "activity" to text("Blank key")),
                mapOf("_clientKey" to text(""), "_entryId" to text(""), "activity" to text("Blank both")),
                mapOf("_clientKey" to text("row-c"), "activity" to text("Real")),
            ),
        )

        assertEquals(
            listOf(cover, "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-c|logPhotos", "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-c|closeUp"),
            out.map { it.key },
        )
        assertEquals(out.size, out.map { it.key }.toSet().size)
        assertTrue(out.none { it.rowKey?.isEmpty() == true })
    }

    @Test
    fun `a row label is trimmed the way the browser trims it, and anything blank falls to its ordinal`() {
        val nbsp = "\u00A0"
        val out = dwIntakeDestinations(
            registry,
            logs(
                mapOf("_clientKey" to text("row-a"), "activity" to text("$nbsp Warping the loom $nbsp")),
                mapOf("_clientKey" to text("row-b"), "activity" to text(nbsp)),
                mapOf("_clientKey" to text("row-c"), "activity" to JsonPrimitive(20496)),
                mapOf("_clientKey" to text("row-d")),
            ),
        )

        assertEquals(
            listOf(
                "13. Prototype Development — Stage logs “Warping the loom” — Photographs",
                "13. Prototype Development — Stage logs “row 2” — Photographs",
                "13. Prototype Development — Stage logs “row 3” — Photographs",
                "13. Prototype Development — Stage logs “row 4” — Photographs",
            ),
            out.filter { it.fieldKey == "logPhotos" }.map { it.label },
        )
    }

    @Test
    fun `an entity that declares no label field names every row by its ordinal`() {
        val unlabelled = registry.copy(
            stages = listOf(
                registry.stages[1].copy(
                    entities = listOf(registry.stages[1].entities[0].copy(labelField = "")),
                ),
            ),
        )
        val out = dwIntakeDestinations(
            unlabelled,
            logs(mapOf("_clientKey" to text("row-a"), "activity" to text("Warping the loom"))),
        )

        assertEquals("13. Prototype Development — Stage logs “row 1” — Photographs", out[0].label)
    }
}
