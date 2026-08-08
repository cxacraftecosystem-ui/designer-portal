package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a chosen reference writes onto the row on THIS surface, and what the phone then prints.
 *
 * ── WHY BOTH HALVES ARE IN ONE FILE ───────────────────────────────────────────────────────────
 *
 * The handset builds its own report from the local draft, with no signal and no server to correct
 * it, so a field that hydrates and never prints — or prints and never hydrates — is a hole in the
 * copy handed to a visiting officer at the close of the workshop, and nothing on the device says
 * so. The tests below therefore walk the same path the paper copy takes: resolve the record onto
 * the row, then render the row and look for the words.
 *
 * ── THE DEFECT THAT MADE THIS FILE NECESSARY ──────────────────────────────────────────────────
 *
 * Hydration here used to write `option.data` into any box whose key happened to match. The server
 * has never worked that way — `REFERENCE_HYDRATION` maps the reference model's vocabulary onto the
 * entity's, and the two are deliberately different — and the collision is not hypothetical: on
 * `existingProduct` the payload's `data["name"]` is the ARTISAN's name under `artisanRef`, while
 * the entity's own `name` field means the PRODUCT. So choosing the artisan wrote her name into the
 * product column of a ministry report, the server's only-fill-blanks rule then refused to correct
 * it at save, and the web — which has always carried the mapping — filled the same row correctly.
 * One surface, one wrong word, permanent.
 */
class DwReferenceHydrationTest {

    // ── The registry, cut to the entities these references fill ──────────────────────────────────

    private fun text(key: String, label: String, role: String = "KEY_VALUE") =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = role)

    private fun ref(key: String, label: String, model: String, hydration: Map<String, String>) =
        FieldDto(
            key = key, label = label, type = "REF", refModel = model,
            reportRole = "HIDDEN", refHydration = hydration,
        )

    /** Stage 5's process step, as the server declares it. */
    private val processStepFields = listOf(
        FieldDto(key = "stepNumber", label = "Step", type = "INT", reportRole = "TABLE_COLUMN"),
        ref(
            "processRef", "Documented process", "Process",
            mapOf("name" to "name", "notes" to "description", "productName" to "documentedFor"),
        ),
        text("name", "Step name", role = "TABLE_COLUMN"),
        text("documentedFor", "Documented for"),
        FieldDto(
            key = "description", label = "What happens", type = "LONG_TEXT",
            reportRole = "TABLE_COLUMN",
        ),
    )

    /** Stage 6's existing product: the entity the name collision lives on. */
    private val existingProductFields = listOf(
        ref("artisanRef", "Artisan", "Artisan", mapOf("name" to "artisanName")),
        ref(
            "productRef", "Documented product", "ProductDocumentation",
            mapOf("name" to "name", "price" to "price", "photo" to "productPhotos"),
        ),
        text("artisanName", "Made by"),
        text("name", "Product"),
        FieldDto(key = "price", label = "Selling price", type = "MONEY"),
        FieldDto(key = "productPhotos", label = "Photographs", type = "IMAGE_LIST"),
    )

    private fun fieldsOf(fields: List<FieldDto>) = fields.associateBy { it.key }

    private fun option(id: String, label: String, data: Map<String, String>) = DwReferenceOption(
        id = id,
        label = label,
        data = buildJsonObject { data.forEach { (k, v) -> put(k, v) } },
    )

    private fun patchFor(
        fields: List<FieldDto>,
        refKey: String,
        option: DwReferenceOption,
        row: Map<String, JsonElement> = emptyMap(),
        lastHydration: Map<String, JsonElement> = emptyMap(),
    ): Map<String, JsonElement?> {
        val writable = fieldsOf(fields)
        val mapping = writable.getValue(refKey).refHydration
        return hydrationPatch(hydratedValues(option, mapping, writable), lastHydration, row, writable)
    }

    // ── The mapping, not the key names ───────────────────────────────────────────────────────────

    @Test
    fun `an artisan chosen on a product row fills Made by and never the product name`() {
        // THE WHOLE DEFECT IN ONE ASSERTION. `data["name"]` is the artisan's; the entity's `name`
        // is the product's. Matching keys put "Latha Devi" in the product column of stage 6.
        val patch = patchFor(
            existingProductFields, "artisanRef",
            option("a1", "Latha Devi", mapOf("name" to "Latha Devi", "village" to "Barpali")),
        )

        assertEquals(JsonPrimitive("Latha Devi"), patch["artisanName"])
        assertFalse(
            "the artisan's name reached the PRODUCT column: $patch",
            patch.containsKey("name"),
        )
        // `village` is not in the mapping and is not a field of this entity either. Nothing about
        // it is written, and nothing about it is invented.
        assertFalse(patch.containsKey("village"))
    }

    @Test
    fun `a documented process fills the step's notes and provenance, not just its name`() {
        val patch = patchFor(
            processStepFields, "processRef",
            option(
                "p1", "Tie and dye",
                mapOf(
                    "name" to "Tie and dye",
                    "notes" to "Yarn is tied in sections, dyed dark, then untied and washed.",
                    "productName" to "Sambalpuri saree",
                ),
            ),
        )

        assertEquals(JsonPrimitive("Tie and dye"), patch["name"])
        assertEquals(
            JsonPrimitive("Yarn is tied in sections, dyed dark, then untied and washed."),
            patch["description"],
        )
        // The picker's sublabel is the only thing that separates this cluster's "Tie and dye" from
        // another's; copying it is what lets the printed page make the same distinction.
        assertEquals(JsonPrimitive("Sambalpuri saree"), patch["documentedFor"])
    }

    @Test
    fun `a field the server published no mapping for hydrates nothing`() {
        // FAIL CLOSED. A missing entry costs the designer one retyped box and the server fills it
        // at save regardless; a guessed one costs a wrong value nobody can see is wrong.
        val fields = listOf(
            FieldDto(key = "sketchRef", label = "From sketch", type = "REF", refModel = "DwSketch"),
            text("name", "Prototype name"),
        )
        val patch = patchFor(fields, "sketchRef", option("s1", "Runner", mapOf("name" to "Runner")))
        assertTrue("an unmapped picker wrote into the row: $patch", patch.isEmpty())
    }

    @Test
    fun `a single photograph bound for a gallery arrives as a list`() {
        // `hydrate_entries` wraps a scalar for a multi field. Writing the bare id would leave an
        // IMAGE_LIST holding something no renderer on this device can read.
        val patch = patchFor(
            existingProductFields, "productRef",
            option("d1", "Saree", mapOf("name" to "Saree", "photo" to "media-7")),
        )
        assertEquals(JsonArray(listOf(JsonPrimitive("media-7"))), patch["productPhotos"])
    }

    @Test
    fun `a gallery the designer filled is never replaced by the catalogue shot`() {
        // Seeded when empty, never overwritten: the photographs taken in the room are the only copy
        // that exists. This is `hydrate_entries`' own rule, restated where the phone applies it.
        val mine = JsonArray(listOf(JsonPrimitive("photo-taken-in-the-room")))
        val patch = patchFor(
            existingProductFields, "productRef",
            option("d1", "Saree", mapOf("photo" to "media-7")),
            row = mapOf("productPhotos" to mine),
        )
        assertFalse("the workshop's own photographs were replaced: $patch",
            patch.containsKey("productPhotos"))
    }

    @Test
    fun `a mis-pick is repaired, and a typed correction is not`() {
        val first = option("p1", "Tie and dye", mapOf("name" to "Tie and dye", "notes" to "Old notes"))
        val firstValues = hydratedValues(first, fieldsOf(processStepFields).getValue("processRef").refHydration, fieldsOf(processStepFields))

        // The designer corrected the step name by hand; the notes are still exactly what we wrote.
        val row = mapOf<String, JsonElement>(
            "name" to JsonPrimitive("Tying and dyeing (as the artisans say it)"),
            "description" to JsonPrimitive("Old notes"),
        )
        val patch = patchFor(
            processStepFields, "processRef",
            option("p2", "Block printing", mapOf("name" to "Block printing", "notes" to "New notes")),
            row = row,
            lastHydration = firstValues,
        )

        assertNull("a correction typed in the room was reverted: $patch", patch["name"])
        assertEquals(JsonPrimitive("New notes"), patch["description"])
    }

    // ── And then it has to be printed ────────────────────────────────────────────────────────────

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is BulletListBlock -> block.items.joinToString("\n") { runText(it) }
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        else -> ""
    }

    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    @Test
    fun `the hydrated process step reaches the report this phone writes`() {
        // HYDRATED IS NOT PRINTED. The office copy and the on-device copy are meant to be the same
        // document, so the same two values the server test follows onto the page are followed here.
        val chosen = option(
            "p1", "Tie and dye",
            mapOf(
                "name" to "Tie and dye",
                "notes" to "Yarn is tied in sections, dyed dark, then untied and washed.",
                "productName" to "Sambalpuri saree",
            ),
        )
        val patch = patchFor(processStepFields, "processRef", chosen)
        val row = buildMap<String, JsonElement> {
            put("stepNumber", JsonPrimitive(1))
            put("processRef", JsonPrimitive("p1"))
            patch.forEach { (key, value) -> if (value != null) put(key, value) }
        }

        val document = buildWorkshopDocument(
            schema = SchemaResponse(
                version = "test",
                stages = listOf(
                    StageDto(
                        number = 5, key = "TRADITIONAL_PROCESS_BASELINE",
                        title = "Traditional Process, Tools & Raw Materials",
                        entities = listOf(
                            EntityDto(
                                key = "processStep", cardinality = "COLLECTION",
                                title = "Process steps", labelField = "name",
                                fields = processStepFields,
                            )
                        ),
                    )
                ),
            ),
            draft = WorkshopDraft(
                workshopId = "local-test",
                title = "Barpali cluster",
                stages = mapOf(
                    "TRADITIONAL_PROCESS_BASELINE" to StageDraft(
                        stageId = "TRADITIONAL_PROCESS_BASELINE",
                        // `rowsFor` filters on the entity prefix a row id carries, so a bare id
                        // belongs to no entity and the stage renders as if the step were never
                        // captured — which is how this assertion first failed.
                        rows = listOf(DraftRow(id = dwRowId("processStep", "r1"), values = row)),
                    )
                ),
            ),
            workshopId = "local-test",
            templateId = "DCH_STANDARD",
            templateName = "DCH standard",
            warnings = emptyList(),
            accent = "",
            imageFor = { null },
        )

        val printed = printedText(document)
        assertTrue(
            "the documented process's notes never reached the page:\n$printed",
            printed.contains("Yarn is tied in sections, dyed dark, then untied and washed."),
        )
        assertTrue(
            "the report cannot tell this cluster's sequence from another's:\n$printed",
            printed.contains("Sambalpuri saree"),
        )
    }
}
