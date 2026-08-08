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

/**
 * `dwIntakeDestinations` — every place the bulk photo intake will let a photograph be filed.
 *
 * WHY THIS FILE EXISTS. [DwPhotoIntakeTest] pins the RANKING, which is the half that decides what to
 * propose; this is the other half, and it decides what a photograph may be written into at all. It
 * was verified by reading only, and reading missed a real defect (below). It is testable because it
 * touches no Android type: a registry and the stage data in, a list of destinations out.
 *
 * THE SPECIFICATION IS `frontend/app/(protected)/design-workshops/[id]/photos/page.tsx`, the
 * `destinations` memo. Every case below is one of its lines, and where the two clients had drifted
 * the WEB is what is asserted:
 *
 *  * `if (!targets.length) continue` — an entity with no image field offers nothing.
 *  * a SINGLETON is offered whether or not this device holds the stage, because a designer may
 *    legitimately file a photograph into a stage they have not opened yet.
 *  * `if (!rowKey) return` — which refuses an EMPTY key and not merely a missing one. This client
 *    refused only null, and `dwDestinationKey` folds null and "" into one string, so two keyless
 *    rows shared a destination key: the picker would have carried two options with one value and
 *    Confirm would have attached the photograph to whichever row came first.
 *  * `typeof labelValue === "string" && labelValue.trim()` — the ordinal fallback, and JavaScript's
 *    trim rather than Kotlin's, which leaves the no-break space a spreadsheet paste carries.
 *  * the ordinal counts EVERY row, skipped ones included, so "row 3" is the third row on both.
 */
class DwIntakeDestinationsTest {

    // -----------------------------------------------------------------------
    // A registry with one singleton photograph field, one collection carrying
    // TWO image fields, and one entity with none at all
    // -----------------------------------------------------------------------

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
                    // No image field anywhere on it, so it must contribute nothing at all rather
                    // than an entry a designer can choose and have nothing happen.
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

    /** The one destination every case carries, because a singleton needs no data to be offered. */
    private val cover = "WORKSHOP_IDENTIFICATION|workshop||coverPhoto"

    // -----------------------------------------------------------------------
    // Singletons
    // -----------------------------------------------------------------------

    @Test
    fun `a singleton offers its image field even when this device holds no data for the stage`() {
        // A designer may legitimately file a photograph into a stage they have not opened yet, and
        // the entity with no image field must not appear beside it.
        val out = dwIntakeDestinations(registry, emptyMap())

        assertEquals(listOf(cover), out.map { it.key })
        assertEquals("1. Workshop Identification — Cover photograph", out[0].label)
        assertNull(out[0].rowKey)
        // An IMAGE holds exactly one, and `defaultDestinationFor` refuses to auto-select it for
        // that reason — a two-hundred-file import would otherwise write each over the last.
        assertFalse(out[0].multiple)
        assertEquals(1, out[0].stageNumber)
        assertEquals("coverPhoto", out[0].fieldKey)
    }

    // -----------------------------------------------------------------------
    // Collections
    // -----------------------------------------------------------------------

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
        // IMAGE_LIST holds many, IMAGE holds one, and the picker's default turns on exactly that.
        assertTrue(out[1].multiple)
        assertFalse(out[2].multiple)
        assertEquals("row-a", out[1].rowKey)
    }

    @Test
    fun `_clientKey addresses the row, and a null one falls through to _entryId`() {
        // The key is what a Confirm aims at, so reading `_clientKey: null` as the string "null"
        // would build a destination addressing a row that does not exist — and `indexOfFirst` at
        // Confirm would report the photograph as missed on a row that is sitting right there.
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

    // -----------------------------------------------------------------------
    // Rows nothing can address
    // -----------------------------------------------------------------------

    @Test
    fun `a row with no key at all contributes no destination, and the ordinals still count it`() {
        // Offering it would be a confirmation that quietly did nothing: there is no key to write
        // the photograph against. The row is still THERE, so the row after it is still the second.
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
        // Second in the collection, and named so — not "row 1" because the keyless one was skipped.
        assertEquals("13. Prototype Development — Stage logs “row 2” — Photographs", out[1].label)
    }

    @Test
    fun `a row whose key is the empty string contributes no destination either`() {
        // THE DEFECT THIS CLASS WAS WRITTEN FOR. `if (!rowKey) return` in page.tsx refuses "" as
        // well as null; a null-only check let it through, and `dwDestinationKey` writes null and ""
        // as the same string — so BOTH rows below produced the key
        // "PROTOTYPE_DEVELOPMENT|prototypeStageLog||logPhotos". `destinationsByKey` keeps the last
        // of two identical keys, the picker cannot say which option is selected, and Confirm's
        // `indexOfFirst` attaches the photograph to whichever row matched "" first.
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
        // The property that matters more than any single key: no two destinations may share one.
        assertEquals(out.size, out.map { it.key }.toSet().size)
        assertTrue(out.none { it.rowKey?.isEmpty() == true })
    }

    // -----------------------------------------------------------------------
    // The row's name
    // -----------------------------------------------------------------------

    @Test
    fun `a row label is trimmed the way the browser trims it, and anything blank falls to its ordinal`() {
        // U+00A0 is what a label pasted out of a spreadsheet carries and `Char.isWhitespace` is
        // deliberately false for it, so Kotlin's own `trim()` would have named the second option
        // with an invisible character where the browser names it "row 2". Written as an escape
        // because a no-break space and a space are indistinguishable in a source file.
        val nbsp = "\u00A0"
        val out = dwIntakeDestinations(
            registry,
            logs(
                mapOf("_clientKey" to text("row-a"), "activity" to text("$nbsp Warping the loom $nbsp")),
                mapOf("_clientKey" to text("row-b"), "activity" to text(nbsp)),
                // Not a string at all. The web tests `typeof labelValue === "string"`, so a number
                // in the label box is no label — reading it as "20496" would name an option after
                // a value nobody typed as a name.
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
