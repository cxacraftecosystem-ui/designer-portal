package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.REPORT_TEMPLATES
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TocBlock
import com.designprototype.workshop.report.renderDocx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * A child collection is printed under the parent record its rows belong to — on the handset.
 *
 * THE DOCUMENT THIS DEFENDS. `EntityDto.parent` ships to this app inside
 * `assets/design-workshop-schema.json` and no Kotlin read it, so a report exported in a cluster
 * printed "Cost sheets" as one table and then "Material cost lines" as a SECOND flat table holding
 * every line of every sheet interleaved in entry order. `costMaterialLine.costSheetRef` — the field
 * that says which line belongs to which sheet — is `reportRole=HIDDEN`, so it was not in that table
 * either. The file handed to the visiting officer contained no way whatsoever to work out which
 * materials cost which product, and the server's copy of the same workshop had it.
 *
 * All three parent-declaring stages are covered: 13 (stage logs and material usage under a
 * prototype), 14 (iterations under a prototype declared in ANOTHER stage) and 17 (cost lines under a
 * cost sheet). `backend/tests/test_report_child_grouping.py` is the same suite against the server and
 * the two are meant to be read side by side.
 *
 * The other half is the half that makes the change safe: nineteen stages declare no parent and every
 * one of them must render exactly as before, which
 * [a stage that declares no parent renders byte-identically to before] pins against the whole
 * shipped registry rather than against a fixture.
 */
class DwParentGroupTest {

    // ── The registry, cut to the two shapes that declare a parent ────────────────────────────────

    private fun text(key: String, label: String, role: String = "TABLE_COLUMN") =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = role)

    private fun ref(key: String, label: String, model: String, role: String) =
        FieldDto(key = key, label = label, type = "REF", refModel = model, reportRole = role)

    /** Stage 17's shape: two child collections and one sibling that belongs to nothing. */
    private fun costingSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 16, key = "FINAL_PROTOTYPE_DOCUMENTATION", title = "Final prototype documentation",
                entities = listOf(
                    EntityDto(
                        key = "finalProduct", name = "DwFinalProduct", cardinality = "COLLECTION",
                        title = "Final products", labelField = "name",
                        fields = listOf(text("name", "Product name")),
                    )
                )
            ),
            StageDto(
                number = 17, key = "COSTING_MARKET_LINKAGE", title = "Costing and market linkage",
                entities = listOf(
                    EntityDto(
                        key = "costSheet", name = "DwCostSheet", cardinality = "COLLECTION",
                        title = "Cost sheets", labelField = "productRef",
                        fields = listOf(
                            ref("productRef", "Product", "DwFinalProduct", "TABLE_COLUMN"),
                            text("totalCost", "Total cost"),
                        ),
                    ),
                    EntityDto(
                        key = "costMaterialLine", name = "DwCostMaterialLine",
                        cardinality = "COLLECTION", title = "Material cost lines",
                        parent = "costSheet", labelField = "item",
                        fields = listOf(
                            // HIDDEN exactly as the registry declares it: the link is invisible in
                            // the printed table, which is what made the flat version unreadable.
                            ref("costSheetRef", "Cost sheet", "DwCostSheet", "HIDDEN"),
                            text("item", "Item"),
                            text("amount", "Amount"),
                        ),
                    ),
                    EntityDto(
                        key = "costLabourLine", name = "DwCostLabourLine",
                        cardinality = "COLLECTION", title = "Labour cost lines",
                        parent = "costSheet", labelField = "task",
                        fields = listOf(
                            ref("costSheetRef", "Cost sheet", "DwCostSheet", "HIDDEN"),
                            text("task", "Task"),
                            text("amount", "Amount"),
                        ),
                    ),
                    EntityDto(
                        key = "buyerLink", name = "DwBuyerLink", cardinality = "COLLECTION",
                        title = "Buyer linkages", labelField = "buyerName",
                        fields = listOf(text("buyerName", "Buyer"), text("interest", "Interest")),
                    ),
                )
            ),
        )
    )

    /**
     * The same stage 17 with the material lines carrying NO table column.
     *
     * The registry says exactly this about an entity whose fields are mostly prose, and it changes
     * the shape of the output completely: with no columns the collection renders as one headed
     * sub-section per record, which is the path where a record heading and a group heading could
     * collide.
     */
    private fun proseCostingSchema(): SchemaResponse {
        val schema = costingSchema()
        return schema.copy(
            stages = schema.stages.map { stage ->
                if (stage.key != "COSTING_MARKET_LINKAGE") stage else stage.copy(
                    entities = stage.entities.map { entity ->
                        if (entity.key != "costMaterialLine") entity else entity.copy(
                            fields = entity.fields.map {
                                it.copy(
                                    reportRole =
                                        if (it.reportRole == "TABLE_COLUMN") "KEY_VALUE" else it.reportRole
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    private fun row(id: String, vararg values: Pair<String, String>) =
        DraftRow(id = id, values = values.associate { (k, v) -> k to JsonPrimitive(v) })

    /**
     * Two cost sheets whose lines are stored INTERLEAVED, plus both kinds of orphan.
     *
     * The interleaving is the point: entry order alone can never reconstruct the grouping, so a test
     * that stored sheet 1's lines together then sheet 2's would pass against the flat table it is
     * meant to reject.
     */
    private fun costingDraft() = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            "FINAL_PROTOTYPE_DOCUMENTATION" to StageDraft(
                stageId = "FINAL_PROTOTYPE_DOCUMENTATION",
                rows = listOf(
                    row("finalProduct#r1", "_entryId" to "fp1", "name" to "Sambalpuri stole"),
                    row("finalProduct#r2", "_entryId" to "fp2", "name" to "Ikat cushion cover"),
                ),
            ),
            "COSTING_MARKET_LINKAGE" to StageDraft(
                stageId = "COSTING_MARKET_LINKAGE",
                rows = listOf(
                    row("costSheet#r1", "_entryId" to "cs1", "productRef" to "fp1", "totalCost" to "1200"),
                    row("costSheet#r2", "_entryId" to "cs2", "productRef" to "fp2", "totalCost" to "640"),

                    row("costMaterialLine#r1", "_entryId" to "m1", "costSheetRef" to "cs1",
                        "item" to "Tussar yarn", "amount" to "750"),
                    row("costMaterialLine#r2", "_entryId" to "m2", "costSheetRef" to "cs2",
                        "item" to "Cotton yarn", "amount" to "240"),
                    row("costMaterialLine#r3", "_entryId" to "m3", "costSheetRef" to "cs1",
                        "item" to "Natural dye", "amount" to "200"),
                    // Names no sheet at all — the picker was never opened.
                    row("costMaterialLine#r4", "_entryId" to "m4", "costSheetRef" to "",
                        "item" to "Zari thread", "amount" to "90"),
                    // Names a sheet that was deleted after this line cited it.
                    row("costMaterialLine#r5", "_entryId" to "m5",
                        "costSheetRef" to "cmsdeletedsheet0001",
                        "item" to "Lining cloth", "amount" to "60"),

                    // Stored in the OPPOSITE order to the sheets, so "groups follow the parent's
                    // order" cannot pass by accident.
                    row("costLabourLine#r1", "_entryId" to "l1", "costSheetRef" to "cs2",
                        "task" to "Weaving", "amount" to "1600"),
                    row("costLabourLine#r2", "_entryId" to "l2", "costSheetRef" to "cs1",
                        "task" to "Dyeing", "amount" to "700"),

                    row("buyerLink#r1", "_entryId" to "b1", "buyerName" to "Tantuja",
                        "interest" to "Bulk order"),
                ),
            ),
        )
    )

    /** Stage 13's two children of one parent, plus stage 14's child of a parent in stage 13. */
    private fun prototypeSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", name = "DwPrototype", cardinality = "COLLECTION",
                        title = "Prototypes", labelField = "name",
                        fields = listOf(text("name", "Prototype name")),
                    ),
                    EntityDto(
                        key = "prototypeStageLog", name = "DwPrototypeStageLog",
                        cardinality = "COLLECTION", title = "Stage logs", parent = "prototype",
                        labelField = "stageName",
                        fields = listOf(
                            ref("prototypeRef", "Prototype", "DwPrototype", "TABLE_COLUMN"),
                            text("stageName", "Stage"),
                        ),
                    ),
                    EntityDto(
                        key = "materialUsage", name = "DwMaterialUsage", cardinality = "COLLECTION",
                        title = "Material usage", parent = "prototype", labelField = "material",
                        fields = listOf(
                            ref("prototypeRef", "Prototype", "DwPrototype", "TABLE_COLUMN"),
                            text("material", "Material"),
                        ),
                    ),
                )
            ),
            StageDto(
                number = 14, key = "PROTOTYPE_ITERATION", title = "Prototype iteration and testing",
                entities = listOf(
                    EntityDto(
                        key = "prototypeIteration", name = "DwPrototypeIteration",
                        cardinality = "COLLECTION", title = "Iterations", parent = "prototype",
                        labelField = "changesMade",
                        fields = listOf(
                            ref("prototypeRef", "Prototype", "DwPrototype", "TABLE_COLUMN"),
                            text("changesMade", "Changes made"),
                        ),
                    )
                )
            ),
        )
    )

    private fun prototypeDraft() = WorkshopDraft(
        workshopId = "local-test",
        stages = mapOf(
            "PROTOTYPE_DEVELOPMENT" to StageDraft(
                stageId = "PROTOTYPE_DEVELOPMENT",
                rows = listOf(
                    row("prototype#r1", "_entryId" to "p1", "name" to "Stole A"),
                    row("prototype#r2", "_entryId" to "p2", "name" to "Cushion B"),
                    row("prototypeStageLog#r1", "_entryId" to "sl1", "prototypeRef" to "p2",
                        "stageName" to "Warping"),
                    row("prototypeStageLog#r2", "_entryId" to "sl2", "prototypeRef" to "p1",
                        "stageName" to "Dyeing"),
                    row("materialUsage#r1", "_entryId" to "mu1", "prototypeRef" to "p1",
                        "material" to "Tussar"),
                    row("materialUsage#r2", "_entryId" to "mu2", "prototypeRef" to "p2",
                        "material" to "Cotton"),
                ),
            ),
            "PROTOTYPE_ITERATION" to StageDraft(
                stageId = "PROTOTYPE_ITERATION",
                rows = listOf(
                    row("prototypeIteration#r1", "_entryId" to "it1", "prototypeRef" to "p1",
                        "changesMade" to "Widened border"),
                    row("prototypeIteration#r2", "_entryId" to "it2", "prototypeRef" to "p2",
                        "changesMade" to "Softer weft"),
                ),
            ),
        )
    )

    private fun build(schema: SchemaResponse, draft: WorkshopDraft): ReportDocument =
        buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = "DCH_STANDARD",
            templateName = "DCH standard",
            warnings = emptyList(),
            accent = "",
            imageFor = { null },
        )

    // ── Reading the built document as a reader meets it ──────────────────────────────────────────

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    /**
     * Each group under [heading] and one string per record printed beneath it.
     *
     * ANCHORED ON THE HEADING'S OWN LEVEL rather than on a fixed H2, so the same helper reads a
     * collection heading and a stage heading. A record is a table row OR a card sub-heading — an
     * entity with no TABLE_COLUMN renders as headed key/value blocks, and a helper that read only
     * tables would report every such group as carrying nothing.
     */
    private fun under(
        document: ReportDocument,
        heading: String,
        cell: (List<List<Run>>) -> String,
    ): List<Pair<String, MutableList<String>>> {
        val blocks = document.blocks.filter { it is HeadingBlock || it is TableBlock }
        val start = blocks.indexOfFirst { it is HeadingBlock && runText(it.runs) == heading }
        assertTrue("no heading reads \"$heading\"; headings were ${headings(document)}", start >= 0)
        val level = (blocks[start] as HeadingBlock).level
        val out = ArrayList<Pair<String, MutableList<String>>>()
        for (block in blocks.drop(start + 1)) {
            when (block) {
                is HeadingBlock -> {
                    if (block.level <= level) break
                    if (block.level == level + 1) out.add(runText(block.runs) to ArrayList())
                    else out.lastOrNull()?.second?.add(runText(block.runs))
                }
                is TableBlock -> out.lastOrNull()?.second?.addAll(block.rows.map(cell))
                else -> Unit
            }
        }
        return out
    }

    /** Each group and the label of every record in it — a row's first cell, or a card's heading. */
    private fun groups(document: ReportDocument, heading: String) =
        under(document, heading) { row -> runText(row.first()) }

    /** As [groups], but a table row is ALL of its cells — an iteration's first column is its parent. */
    private fun groupRows(document: ReportDocument, heading: String) =
        under(document, heading) { row -> row.joinToString(" ") { runText(it) } }

    private fun headings(document: ReportDocument) =
        document.blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs) }

    private fun headingBlocks(document: ReportDocument, under: String): List<HeadingBlock> {
        val all = document.blocks.filterIsInstance<HeadingBlock>()
        val start = all.indexOfFirst { runText(it.runs) == under }
        val level = all[start].level
        return all.drop(start + 1).takeWhile { it.level > level }.filter { it.level == level + 1 }
    }

    private fun tableRows(document: ReportDocument, caption: String): List<List<String>> =
        document.blocks.filterIsInstance<TableBlock>()
            .filter { it.caption == caption }
            .map { table -> table.rows.map { runText(it.first()) } }

    // ── Stage 17: the cost sheet ─────────────────────────────────────────────────────────────────

    @Test
    fun `material lines are printed under the cost sheet they belong to`() {
        // THE DEFECT. Fails before the change with a single five-row "Material cost lines" table in
        // which nothing says that Tussar yarn and Natural dye are what the Sambalpuri stole cost.
        val found = groups(build(costingSchema(), costingDraft()), "Material cost lines")
        assertEquals(
            listOf(
                "Cost sheets — Sambalpuri stole",
                "Cost sheets — Ikat cushion cover",
                "No cost sheet recorded",
            ),
            found.map { it.first },
        )
        assertEquals(listOf("Tussar yarn", "Natural dye"), found[0].second.toList())
        assertEquals(listOf("Cotton yarn"), found[1].second.toList())
    }

    @Test
    fun `no group carries a line belonging to another sheet`() {
        // The whole value of the grouping. A line under the wrong heading is worse than a flat
        // table: a flat table is merely unusable, a mis-filed line is a wrong number an officer
        // would act on.
        val document = build(costingSchema(), costingDraft())
        val owned = mapOf(
            "Material cost lines" to mapOf(
                "Cost sheets — Sambalpuri stole" to setOf("Tussar yarn", "Natural dye"),
                "Cost sheets — Ikat cushion cover" to setOf("Cotton yarn"),
            ),
            "Labour cost lines" to mapOf(
                "Cost sheets — Sambalpuri stole" to setOf("Dyeing"),
                "Cost sheets — Ikat cushion cover" to setOf("Weaving"),
            ),
        )
        for ((caption, expected) in owned) {
            for ((name, lines) in groups(document, caption)) {
                expected[name]?.let { assertEquals("$caption: $name", it, lines.toSet()) }
            }
        }
    }

    @Test
    fun `groups follow the parents own order not the order the lines were typed`() {
        // The labour lines are stored sheet-2-first; ordering by the children would print the Ikat
        // cushion cover above the Sambalpuri stole and a reader comparing the two tables would have
        // to search.
        assertEquals(
            listOf("Cost sheets — Sambalpuri stole", "Cost sheets — Ikat cushion cover"),
            groups(build(costingSchema(), costingDraft()), "Labour cost lines").map { it.first },
        )
    }

    @Test
    fun `every line that was recorded is still printed exactly once`() {
        // Grouping must not become a filter. A line that cost real money silently missing from a
        // total is the failure that would make this change worse than the defect it fixes.
        val printed = groups(build(costingSchema(), costingDraft()), "Material cost lines")
            .flatMap { it.second }
        assertEquals(
            listOf("Cotton yarn", "Lining cloth", "Natural dye", "Tussar yarn", "Zari thread"),
            printed.sorted(),
        )
    }

    @Test
    fun `a line naming no cost sheet is printed under a heading that says so`() {
        // AN ORPHAN IS FIELDWORK SOMEBODY COLLECTED. Filing it under a sheet it does not belong to
        // would be a fabrication and dropping it would delete a measurement, so it gets an honest
        // heading of its own — and it comes last, after every real sheet.
        val found = groups(build(costingSchema(), costingDraft()), "Material cost lines")
        assertEquals("No cost sheet recorded", found.last().first)
        // Both kinds of orphan land here: the blank ref, then the one naming a deleted sheet.
        assertEquals(listOf("Zari thread", "Lining cloth"), found.last().second.toList())
    }

    @Test
    fun `an orphan heading is not printed when every line names its sheet`() {
        // The honest heading must not become a permanent empty fixture at the foot of every table.
        val draft = costingDraft()
        val stage = draft.stages.getValue("COSTING_MARKET_LINKAGE")
        val trimmed = draft.copy(
            stages = draft.stages + ("COSTING_MARKET_LINKAGE" to stage.copy(
                rows = stage.rows.filterNot { it.id in setOf("costMaterialLine#r4", "costMaterialLine#r5") }
            ))
        )
        assertEquals(
            listOf("Cost sheets — Sambalpuri stole", "Cost sheets — Ikat cushion cover"),
            groups(build(costingSchema(), trimmed), "Material cost lines").map { it.first },
        )
    }

    @Test
    fun `a sheet with no lines of its own gets no empty heading`() {
        // A sheet nobody itemised is reported by its absence from the breakdown, not by a heading
        // with an empty table under it that reads as "this product cost nothing".
        val draft = costingDraft()
        val stage = draft.stages.getValue("COSTING_MARKET_LINKAGE")
        val oneLabourLine = draft.copy(
            stages = draft.stages + ("COSTING_MARKET_LINKAGE" to stage.copy(
                rows = stage.rows.filterNot { it.id == "costLabourLine#r1" }
            ))
        )
        assertEquals(
            listOf("Cost sheets — Sambalpuri stole"),
            groups(build(costingSchema(), oneLabourLine), "Labour cost lines").map { it.first },
        )
    }

    @Test
    fun `a sheet that has not synced yet claims none of the orphans`() {
        /*
         * A SHEET WITH NO SERVER ID CANNOT OWN A LINE THAT NAMES NO SHEET, and to a map the two look
         * identical: the orphan bucket is keyed by the empty string and an unsynced row's `_entryId`
         * IS the empty string. On this client that is not an exotic state — it is EVERY row created
         * in a courtyard, because `_entryId` is written only by a sync. Letting the blank id into
         * the owner index printed every unattributed line in the workshop as that one product's
         * material cost: a fabricated number in the column an officer sanctions against, produced by
         * the code that exists to prevent fabrications.
         *
         * Fails without the blank guard with "Zari thread" — a line naming no sheet at all — heading
         * up as the Sambalpuri stole's breakdown.
         */
        val draft = costingDraft()
        val stage = draft.stages.getValue("COSTING_MARKET_LINKAGE")
        val unsynced = draft.copy(
            stages = draft.stages + ("COSTING_MARKET_LINKAGE" to stage.copy(
                rows = stage.rows.map { r ->
                    if (r.id != "costSheet#r1") r
                    // The sheet keeps its local key, so its own lines still find it; what it loses
                    // is the server id, exactly as an unsynced sheet has never had one.
                    else r.copy(values = r.values - "_entryId")
                }
            ))
        )
        val found = groups(build(costingSchema(), unsynced), "Material cost lines")
        assertEquals(
            listOf("Cost sheets — Ikat cushion cover", "No cost sheet recorded"),
            found.map { it.first },
        )
        // Its own lines still name it by `cs1`, which nothing holds any more, so they are reported
        // as attributable to nothing rather than being dropped — and nothing is lost either way.
        assertEquals(
            setOf("Zari thread", "Lining cloth", "Tussar yarn", "Natural dye"),
            found.last().second.toSet(),
        )
        assertEquals(
            listOf("Cotton yarn", "Lining cloth", "Natural dye", "Tussar yarn", "Zari thread"),
            found.flatMap { it.second }.sorted(),
        )
    }

    @Test
    fun `a line entered offline finds its sheet through the local row key`() {
        // THE CLIENT-ONLY HALF OF THE JOIN. A workshop authored entirely in a courtyard has no
        // server ids at all, so the picker stored the parent's local `DraftRow` key in the line.
        // Joining on `_entryId` alone would report every line of every offline workshop — which is
        // the workshop this export path exists for — as unattributed.
        val schema = costingSchema()
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "COSTING_MARKET_LINKAGE" to StageDraft(
                    stageId = "COSTING_MARKET_LINKAGE",
                    rows = listOf(
                        row("costSheet#kzzq1p8n4m2wd7xr", "totalCost" to "1200"),
                        row("costMaterialLine#r1", "costSheetRef" to "kzzq1p8n4m2wd7xr",
                            "item" to "Tussar yarn", "amount" to "750"),
                    ),
                )
            )
        )
        val found = groups(build(schema, draft), "Material cost lines")
        assertEquals(listOf("Cost sheets 1"), found.map { it.first })
        assertEquals(listOf("Tussar yarn"), found.single().second.toList())
    }

    @Test
    fun `a sibling collection with no parent is left flat in the same stage`() {
        // Grouping is a property of the ENTITY, not of the stage. Stage 17 holds both kinds, and
        // buyer linkages belong to no cost sheet — one flat table is the right shape for them.
        val document = build(costingSchema(), costingDraft())
        assertEquals(emptyList<String>(), groups(document, "Buyer linkages").map { it.first })
        assertEquals(listOf(listOf("Tantuja")), tableRows(document, "Buyer linkages"))
        // And the parent collection itself stays one table of all the sheets.
        assertEquals(
            listOf(listOf("Sambalpuri stole", "Ikat cushion cover")),
            tableRows(document, "Cost sheets"),
        )
    }

    @Test
    fun `a group is headed by the same words as the sheet it belongs to`() {
        // A cost sheet is titled by its `productRef`, which is an id. Both the sheet's own row in
        // the table above and its group heading go through the same `rowHeading`, so a reader
        // matches them by eye — and neither prints the cuid.
        val document = build(costingSchema(), costingDraft())
        val sheets = tableRows(document, "Cost sheets").single()
        val names = groups(document, "Material cost lines").map { it.first }
        assertEquals(listOf("Sambalpuri stole", "Ikat cushion cover"), sheets)
        assertEquals(sheets, names.take(2).map { it.removePrefix("Cost sheets — ") })
        assertFalse("a group heading printed a raw record id: $names", names.any { it.contains("cs1") })
    }

    @Test
    fun `a sheet that names no product still heads its group readably`() {
        // The positional fallback. A sheet whose product was never picked is "Cost sheets 2" in the
        // table above and "Cost sheets 2" over its lines, rather than a blank heading.
        val draft = costingDraft()
        val stage = draft.stages.getValue("COSTING_MARKET_LINKAGE")
        val nameless = draft.copy(
            stages = draft.stages + ("COSTING_MARKET_LINKAGE" to stage.copy(
                rows = stage.rows.map { r ->
                    if (r.id != "costSheet#r2") r else r.copy(values = r.values - "productRef")
                }
            ))
        )
        assertEquals(
            "Cost sheets 2",
            groups(build(costingSchema(), nameless), "Material cost lines")[1].first,
        )
    }

    // ── Stages 13 and 14: the prototype, including a parent in another stage ─────────────────────

    @Test
    fun `stage logs and material usage are grouped under their prototype`() {
        // Stage 13 declares TWO children of the same parent; both must group, independently.
        //
        // Every template renders stage 13 as CARDS, so a record here is a sub-heading with its
        // fields under it rather than a table row — which is exactly why [under] treats a heading a
        // level below the group as a record. Reading only tables reported every group as empty.
        val document = build(prototypeSchema(), prototypeDraft())
        assertEquals(
            listOf("Prototypes — Stole A" to listOf("Stage logs — Dyeing"),
                   "Prototypes — Cushion B" to listOf("Stage logs — Warping")),
            groups(document, "Stage logs").map { it.first to it.second.toList() },
        )
        assertEquals(
            listOf("Prototypes — Stole A" to listOf("Material usage — Tussar"),
                   "Prototypes — Cushion B" to listOf("Material usage — Cotton")),
            groups(document, "Material usage").map { it.first to it.second.toList() },
        )
    }

    @Test
    fun `an iteration is grouped under a prototype declared in another stage`() {
        // STAGE 14'S PARENT LIVES IN STAGE 13. The child's own stage holds no `prototype` rows at
        // all, so a lookup that searched only the current stage would find no parents and print
        // every iteration as an orphan.
        //
        // READ FROM THE STAGE HEADING. Stage 14 declares one collection, so no entity heading is
        // printed at all and the groups answer to the stage's own H1 — "Iterations" is the entity's
        // title and reaches the page only as a table caption, never as a heading.
        val document = build(prototypeSchema(), prototypeDraft())
        assertFalse(
            "\"Iterations\" should not be a heading in a one-collection stage",
            headings(document).any { it == "Iterations" },
        )
        val found = groupRows(document, "Prototype iteration and testing")
        assertEquals(listOf("Prototypes — Stole A", "Prototypes — Cushion B"), found.map { it.first })
        val byName = found.associate { it.first to it.second.joinToString(" ") }
        assertTrue(byName.getValue("Prototypes — Stole A").contains("Widened border"))
        assertTrue(byName.getValue("Prototypes — Cushion B").contains("Softer weft"))
        // Neither prototype carries the other's change, which is the whole point of the grouping.
        assertFalse(byName.getValue("Prototypes — Stole A").contains("Softer weft"))
        assertFalse(byName.getValue("Prototypes — Cushion B").contains("Widened border"))
    }

    @Test
    fun `an iteration whose prototype is missing is still printed`() {
        // The same honesty rule as the cost line, on a stage whose parent is loaded from elsewhere —
        // if stage 13 was never filled in on this device, every iteration would otherwise vanish.
        val draft = prototypeDraft()
        val orphaned = draft.copy(stages = draft.stages - "PROTOTYPE_DEVELOPMENT")
        val found = groupRows(build(prototypeSchema(), orphaned), "Prototype iteration and testing")
        assertEquals(listOf("No prototype recorded"), found.map { it.first })
        val printed = found.single().second.joinToString(" ")
        assertTrue(printed.contains("Widened border"))
        assertTrue(printed.contains("Softer weft"))
    }

    // ── Heading levels and numbering ─────────────────────────────────────────────────────────────

    @Test
    fun `a group heading never outranks the collection it is part of and stays within level four`() {
        // A group at the collection's own level would read as a new collection and would renumber
        // the rest of the report. A level BELOW four would crash the export outright:
        // `DocxWriter.emitHeading` indexes a four-element spacing table by level.
        val document = build(costingSchema(), costingDraft())
        val collection = document.blocks.filterIsInstance<HeadingBlock>()
            .single { runText(it.runs) == "Material cost lines" }
        assertEquals(2, collection.level)
        assertTrue(headingBlocks(document, "Material cost lines").all { it.level == 3 })
        assertTrue(
            "a heading escaped the 1..4 the writers style",
            document.blocks.filterIsInstance<HeadingBlock>().all { it.level in 1..4 },
        )
    }

    @Test
    fun `the contents page gets the groups and not the records`() {
        /*
         * THE PDF'S NAVIGATION, PINNED AT THE ONLY LEVEL THIS SOURCE SET CAN REACH.
         * `android.graphics.pdf.PdfDocument` writes no bookmark outline, so on this client the
         * outline IS the rendered contents page: `PdfWriter.blockHeading` records one `TocEntry` per
         * HeadingBlock during the measuring pass and `blockToc` prints those with
         * `entry.level > block.depth` skipped, where [TocBlock.depth] is 3. The same number is the
         * .docx's ` TOC \o "1-3" ` field.
         *
         * So a group at level 3 is in the contents of both files and a record at level 4 is not,
         * which is the behaviour worth having: forty material lines under one sheet must not each
         * become a row of the table of contents.
         *
         * This is an assertion about the document, not about PDF bytes — see the note in the report.
         */
        val document = build(costingSchema(), costingDraft())
        val depth = document.blocks.filterIsInstance<TocBlock>().firstOrNull()?.depth ?: 3
        assertEquals(3, depth)
        val listed = document.blocks.filterIsInstance<HeadingBlock>()
            .filter { it.level <= depth }
            .map { runText(it.runs) }
        assertTrue("the groups are missing from the contents: $listed",
            listed.containsAll(listOf("Cost sheets — Sambalpuri stole", "No cost sheet recorded")))

        // And the records inside a group stay out of it. Read off the CARDS shape, because that is
        // the one that emits a heading per record at all.
        val cards = build(proseCostingSchema(), costingDraft())
        val records = cards.blocks.filterIsInstance<HeadingBlock>()
            .filter { runText(it.runs).startsWith("Material cost lines — ") }
        assertTrue("no record headings to check", records.isNotEmpty())
        assertTrue("a record heading would flood the contents page",
            records.all { it.level > depth })
    }

    @Test
    fun `group headings are numbered beneath the collection they belong to`() {
        // The sub-headings join the "3.2.1" counters the table of contents and the Word outline are
        // built from. An unnumbered stray here breaks both.
        val document = build(costingSchema(), costingDraft())
        val collection = document.blocks.filterIsInstance<HeadingBlock>()
            .single { runText(it.runs) == "Material cost lines" }
        assertEquals(
            listOf("${collection.number}.1", "${collection.number}.2", "${collection.number}.3"),
            headingBlocks(document, "Material cost lines").map { it.number },
        )
        // The collection AFTER the groups keeps its own place in the sequence, so the groups have
        // not consumed a number that belonged to a sibling and shifted the rest of the report.
        val labour = document.blocks.filterIsInstance<HeadingBlock>()
            .single { runText(it.runs) == "Labour cost lines" }
        val head = collection.number.substringBeforeLast(".")
        val last = collection.number.substringAfterLast(".").toInt()
        assertEquals("$head.${last + 1}", labour.number)
    }

    @Test
    fun `a card falls below its group rather than beside it`() {
        // An entity with no TABLE_COLUMN renders as one headed block per record. Those record
        // headings used to be fixed at level 3, which is the level a group heading now occupies —
        // leaving them there would have made every record read as a group of its own.
        val document = build(proseCostingSchema(), costingDraft())
        val levels = document.blocks.filterIsInstance<HeadingBlock>()
            .dropWhile { runText(it.runs) != "Material cost lines" }
            .drop(1)
            .takeWhile { it.level > 2 }
            .map { it.level }
        assertEquals("expected a group then its records", listOf(3, 4, 4), levels.take(3))
    }

    // ── The nineteen stages that declare no parent ───────────────────────────────────────────────

    /**
     * The registry the APK actually ships, read off disk rather than hand-built.
     *
     * A fixture cannot prove this invariant: the claim is about the 22 stages a designer will export
     * tonight, not about three shapes I chose. The asset is a hard requirement of the app itself
     * (`StageSchemaStore.load` treats its absence as a fatal error rather than an empty registry),
     * so a test that skipped when it could not be found would be a test that proves nothing on the
     * day somebody moves it.
     */
    private fun shippedRegistry(): SchemaResponse {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "src/main/assets/design-workshop-schema.json"),
                File(dir, "app/src/main/assets/design-workshop-schema.json"),
                File(dir, "android/app/src/main/assets/design-workshop-schema.json"),
            )) {
                if (candidate.isFile) {
                    return Json { ignoreUnknownKeys = true }
                        .decodeFromString(SchemaResponse.serializer(), candidate.readText())
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("design-workshop-schema.json not found from ${File(".").absolutePath}")
    }

    /** One deterministic value per field, so a stage can be filled without naming its schema. */
    private fun filler(field: FieldDto): JsonElement? = when (field.type) {
        "IMAGE", "IMAGE_LIST", "FILE", "AUDIO", "VIDEO", "GEO", "RICH_TEXT" -> null
        "INT", "DECIMAL", "MONEY", "PERCENT" -> JsonPrimitive(7)
        "BOOL" -> JsonPrimitive(true)
        "DATE" -> JsonPrimitive("2026-02-03")
        "TIME" -> JsonPrimitive("10:30")
        "ENUM" -> field.options.firstOrNull()?.let { JsonPrimitive(it.value) }
        "MULTI_ENUM", "TAGS" -> null
        // A REF is filled with the id of the FIRST row of every collection, which is how the
        // grouped stages come out non-trivially grouped rather than all-orphan.
        "REF" -> JsonPrimitive("e0")
        else -> JsonPrimitive("${field.key} value")
    }

    /** Every stage of the shipped registry, three rows per collection, all fields answered. */
    private fun filledDraft(schema: SchemaResponse) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = schema.stages.associate { stage ->
            stage.key to StageDraft(
                stageId = stage.key,
                order = stage.number,
                values = stage.entities.filter { it.cardinality == "SINGLETON" }
                    .flatMap { it.fields }
                    .mapNotNull { field -> filler(field)?.let { field.key to it } }
                    .toMap(),
                rows = stage.entities.filter { it.cardinality != "SINGLETON" }.flatMap { entity ->
                    (0 until 3).map { index ->
                        DraftRow(
                            id = "${entity.key}#r$index",
                            values = entity.fields
                                .mapNotNull { field -> filler(field)?.let { field.key to it } }
                                .toMap() + ("_entryId" to JsonPrimitive("e$index")),
                        )
                    }
                },
            )
        }
    )

    @Test
    fun `a stage that declares no parent renders byte-identically to before`() {
        /*
         * THE TEST THAT MAKES THE CHANGE SAFE RATHER THAN MERELY CORRECT. `buildWorkshopDocument` is
         * the path every stage of every on-device report goes through, so a regression here is a
         * regression in all 22 at once.
         *
         * Stripping `parent` from the registry IS the pre-change code path, exactly and provably:
         * `dwParentGroups` returns `null` on its first line for a blank parent, and the caller's
         * null branch is character-for-character the single `renderCollection` call this loop made
         * before the grouping existed. So the stripped build is the old document, the shipped build
         * is the new one, and every stage that declares no parent must produce the identical run of
         * blocks — heading numbers, table columns, captions, paragraph styles and all, not an
         * outline-level summary that would miss every one of them.
         *
         * ONE FIELD IS EXCEPTED AND IT IS NOT A WEAKENING — see [anonymised]. This assertion as
         * originally written was RED: `bookmarkId` appends the block's INDEX to each anchor name, so
         * the three group headings the grouping inserts into stages 13 and 14 shifted the bookmark of
         * every heading after them, and stage 15 — which declares no parent and prints exactly what
         * it always printed — failed on `_S135_…` against `_S138_…`. Nothing a reader can see moved.
         */
        val schema = shippedRegistry()
        val stripped = schema.copy(
            stages = schema.stages.map { stage ->
                stage.copy(entities = stage.entities.map { it.copy(parent = "") })
            }
        )
        val draft = filledDraft(schema)
        val before = build(stripped, draft).blocks
        val after = build(schema, draft).blocks

        val withParents = schema.stages
            .filter { stage -> stage.entities.any { it.parent.isNotBlank() } }
            .map { it.key }
        assertEquals(
            "the shipped registry's parent-declaring stages changed",
            listOf("PROTOTYPE_DEVELOPMENT", "PROTOTYPE_ITERATION", "COSTING_MARKET_LINKAGE"),
            withParents,
        )

        var compared = 0
        for (stage in schema.stages) {
            if (stage.key in withParents) continue
            val expected = sectionOf(before, stage.title)
            assertEquals("${stage.key} moved", anonymised(expected), anonymised(sectionOf(after, stage.title)))
            if (expected.isNotEmpty()) compared += 1
        }
        // Not a vacuous pass over nineteen empty sections: the filler answers enough of the
        // registry that the great majority of the stages actually printed something to compare.
        assertTrue("only $compared parent-free stages produced any blocks", compared >= 15)
        // Guard against the whole comparison being vacuous: the three grouped stages MUST differ,
        // and the document must actually carry tables.
        assertNotEquals(before, after)
        assertTrue(before.filterIsInstance<TableBlock>().size > 10)

        // THE JOB THE ORDINAL DOES, CHECKED RATHER THAN NORMALISED AWAY. [anonymised] drops the
        // bookmark from the comparison, so this is the assertion that stops it hiding a real defect:
        // the anchor names must still be unique across the whole grouped document. Two headings
        // sharing one would send a table-of-contents row, in Word and in the PDF alike, to the wrong
        // section — and the grouping is exactly the change that adds repeated heading TEXT, because
        // one cost sheet heads a group of material lines and a group of labour lines under the same
        // name.
        val bookmarks = after.filterIsInstance<HeadingBlock>().map { it.bookmark }
        assertEquals(
            "two headings of the grouped document share a bookmark: " +
                bookmarks.groupingBy { it }.eachCount().filterValues { it > 1 }.keys,
            bookmarks.size,
            bookmarks.toSet().size,
        )
    }

    /**
     * A run of blocks with each heading's BOOKMARK blanked, and nothing else touched.
     *
     * Everything a reader meets is still compared verbatim — heading text, heading number, table
     * columns and widths, captions, paragraph styles, runs and their marks. The bookmark is the one
     * field that cannot be equal and whose inequality means nothing: `bookmarkId` appends the block's
     * index to the anchor name so that two identically titled headings do not collide, so inserting
     * a heading ANYWHERE renames the anchor of every heading after it. It is an internal name — the
     * .docx `TOC` field and the PDF's own contents are generated from the same block list in the same
     * pass and point at whatever it says, and two exports of the SAME workshop still agree because
     * the same block list yields the same indices.
     */
    private fun anonymised(blocks: List<Block>): List<Block> =
        blocks.map { if (it is HeadingBlock) it.copy(bookmark = "") else it }

    /** Every block from a stage's H1 up to the next H1, as the reader meets it. */
    private fun sectionOf(blocks: List<Block>, title: String): List<Block> {
        val start = blocks.indexOfFirst { it is HeadingBlock && it.level == 1 && runText(it.runs) == title }
        if (start < 0) return emptyList()
        val rest = blocks.drop(start + 1)
        return listOf(blocks[start]) + rest.takeWhile { !(it is HeadingBlock && it.level == 1) }
    }

    @Test
    fun `every collection of the shipped registry keeps every one of its rows`() {
        // NO ROW IS EVER LOST, asserted over the real 22 stages rather than over a fixture: the
        // number of body rows printed for each collection is the same grouped as flat.
        val schema = shippedRegistry()
        val stripped = schema.copy(
            stages = schema.stages.map { stage ->
                stage.copy(entities = stage.entities.map { it.copy(parent = "") })
            }
        )
        val draft = filledDraft(schema)
        fun rowsByCaption(document: ReportDocument) = document.blocks
            .filterIsInstance<TableBlock>()
            .groupBy { it.caption }
            .mapValues { (_, tables) -> tables.sumOf { it.rows.size } }
        assertEquals(rowsByCaption(build(stripped, draft)), rowsByCaption(build(schema, draft)))
    }

    @Test
    fun `dwParentGroups declines every entity of the shipped registry that has no parent`() {
        // The mechanism the test above depends on, stated directly: 38 of the 43 entities take the
        // untouched path, and it is `null` — not a one-group list that happens to look the same.
        val schema = shippedRegistry()
        val draft = filledDraft(schema)
        var declined = 0
        for (stage in schema.stages) {
            for (entity in stage.entities) {
                if (entity.parent.isNotBlank()) continue
                assertNull(
                    "${entity.key} was grouped",
                    dwParentGroups(
                        schema = schema, draft = draft, entity = entity,
                        rows = listOf(mapOf("x" to JsonPrimitive("y"))),
                        parentHeading = { _, _, _ -> "" }, refLabel = { "" },
                    ),
                )
                declined += 1
            }
        }
        assertEquals(38, declined)
    }

    // ── The artefact a reader actually opens ─────────────────────────────────────────────────────

    /**
     * The .docx the officer's Word opens, read out of the archive.
     *
     * A block-list assertion does not prove the file is right: the writer could drop a heading level
     * or emit the tables in another order. [DocxWriter] is pure JVM (a zip and a string builder), so
     * this runs on the desktop with no device.
     */
    private fun docxText(document: ReportDocument): String {
        val out = ByteArrayOutputStream()
        renderDocx(document, { null }, out)
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") return zip.readBytes().decodeToString()
            }
        }
        throw AssertionError("the .docx carried no word/document.xml")
    }

    @Test
    fun `the docx shows each sheets lines under that sheets heading`() {
        val xml = docxText(build(costingSchema(), costingDraft()))
        // SCOPED TO THE SECTION on purpose: "Ikat cushion cover" is also a final product at stage 16
        // and a row of the cost sheet table above these lines, so a first-occurrence search across
        // the whole file compares the wrong strings and fails on a document that is perfectly right.
        val section = xml.substring(
            xml.indexOf("Material cost lines"),
            xml.indexOf("Labour cost lines"),
        )
        val expected = listOf(
            "Sambalpuri stole", "Tussar yarn", "Natural dye",
            "Ikat cushion cover", "Cotton yarn",
            "No cost sheet recorded", "Zari thread", "Lining cloth",
        )
        val found = expected.map { needle ->
            val at = section.indexOf(needle)
            assertTrue("$needle never reached the material cost section", at >= 0)
            at
        }
        assertEquals("out of order: ${expected.zip(found)}", found.sorted(), found)
        // The group headings are real Word headings, so they reach the navigation pane and a
        // generated table of contents rather than looking like bold paragraphs. `outlineLvl` is
        // what the TOC field ( \o "1-3" ) selects on.
        assertTrue(section.contains("w:val=\"Heading3\""))
        assertTrue(section.contains("<w:outlineLvl w:val=\"2\"/>"))
    }

    @Test
    fun `the docx numbers the group headings in sequence with the rest of the report`() {
        // The numbers are written into the run text, so a heading that lost its number would print
        // an unnumbered line in the middle of a numbered document and a TOC row with no number.
        val document = build(costingSchema(), costingDraft())
        val xml = docxText(document)
        val numbers = headingBlocks(document, "Material cost lines").map { it.number }
        for (number in numbers) {
            assertTrue("the .docx lost the number $number", xml.contains(">$number. <"))
        }
    }

    // ── All six templates the picker offers ──────────────────────────────────────────────────────

    /**
     * The registry with `parent` stripped from every entity — THE PRE-CHANGE CODE PATH, exactly.
     *
     * `dwParentGroups` returns `null` on its first line for a blank parent and the caller's null
     * branch is the single `renderCollection` call this builder made before the grouping existed, so
     * a document built from this is the document the handset used to produce. Every assertion below
     * that names the new behaviour also names what this produces instead, which is what stops the
     * test from being one that would pass against unfixed code.
     */
    private fun withoutParents(schema: SchemaResponse) = schema.copy(
        stages = schema.stages.map { stage ->
            stage.copy(entities = stage.entities.map { it.copy(parent = "") })
        }
    )

    /**
     * The costing fixture with every field at the BASIC tier.
     *
     * `COMPACT_SUMMARY` caps the report at Basic and the fixture's fields default to Standard, so
     * under that one template every column would be dropped before the grouping was reached and the
     * cross-template comparison would be an empty document matching an empty document. Tiers are not
     * what these tests are about — the six SECTION LISTS are — so the tier is taken out of the way.
     */
    private fun basicCostingSchema(): SchemaResponse {
        val schema = costingSchema()
        return schema.copy(
            stages = schema.stages.map { stage ->
                stage.copy(entities = stage.entities.map { entity ->
                    entity.copy(fields = entity.fields.map { it.copy(tier = "BASIC") })
                })
            }
        )
    }

    /** [build] with the template named, rather than fixed at the DCH standard. */
    private fun buildAs(schema: SchemaResponse, draft: WorkshopDraft, templateId: String) =
        buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = templateId,
            templateName = templateId,
            warnings = emptyList(),
            accent = "",
            imageFor = { null },
        )

    /** The material section as a reader meets it, top to bottom: each sheet, then that sheet's lines. */
    private val materialSection = listOf(
        "Sambalpuri stole", "Tussar yarn", "Natural dye",
        "Ikat cushion cover", "Cotton yarn",
        "No cost sheet recorded", "Zari thread", "Lining cloth",
    )

    @Test
    fun `every template the picker offers groups the lines under their own sheet`() {
        /*
         * THE PICKER OFFERS SIX FORMATS AND THE SUITE ABOVE EXERCISES ONE. Each of the six declares
         * its own section list and its own shape for stage 17: the photo catalogue heads it "Price
         * list" and numbers nothing, the compact summary caps the report at the Basic tier, the
         * agency format drops fifteen stages and reorders what is left. The grouping sits inside the
         * one loop all six go through, so "the cost breakdown is readable" is either true of all six
         * or it is a property of one fixture. A designer prints whichever format the office asked
         * for, and finds out which it was only when the file is already on the officer's desk.
         *
         * Each template is checked against ITS OWN pre-change document rather than against a
         * constant, so the assertion fails from both directions: grouping that stopped working falls
         * back to the flat list, and a flat list that somehow satisfied the first assertion could not
         * also satisfy the second.
         */
        val schema = basicCostingSchema()
        val flat = withoutParents(schema)
        val draft = costingDraft()
        for (template in REPORT_TEMPLATES) {
            val found = groups(buildAs(schema, draft, template.id), "Material cost lines")
            assertEquals(
                "${template.id} did not group the material lines under their sheets",
                listOf(
                    "Cost sheets — Sambalpuri stole",
                    "Cost sheets — Ikat cushion cover",
                    "No cost sheet recorded",
                ),
                found.map { it.first },
            )
            assertEquals(
                "${template.id} filed a line under a sheet it does not belong to",
                listOf(
                    listOf("Tussar yarn", "Natural dye"),
                    listOf("Cotton yarn"),
                    listOf("Zari thread", "Lining cloth"),
                ),
                found.map { it.second.toList() },
            )
            // What this template printed BEFORE the grouping: no group headings at all, and the same
            // five lines in one flat run. Nothing is added and nothing is lost — the change is the
            // arrangement.
            val before = groups(buildAs(flat, draft, template.id), "Material cost lines")
            assertEquals("${template.id} was already grouped without a parent", emptyList<String>(),
                before.map { it.first })
            assertEquals(
                "${template.id} lost or gained a line",
                tableRows(buildAs(flat, draft, template.id), "Material cost lines").flatten().sorted(),
                found.flatMap { it.second }.sorted(),
            )
        }
    }

    @Test
    fun `a template that numbers nothing leaves its group headings unnumbered`() {
        /*
         * PHOTO_CATALOGUE sets `numberHeadings = false`, and the group heading is the one heading in
         * the document this builder adds on its own account. Numbering it anyway would put the only
         * numbered line in an unnumbered document into the buyer-facing format — the backend pins the
         * same invariant in `test_group_headings_carry_no_number_in_a_template_that_numbers_nothing`.
         *
         * Asserted in BOTH directions against the DCH standard, so a group heading hard-coded either
         * way fails: unconditionally numbered fails the catalogue, unconditionally bare fails the DCH.
         */
        val schema = basicCostingSchema()
        val catalogue = buildAs(schema, costingDraft(), "PHOTO_CATALOGUE")
        // Not vacuous: there are three group headings in this document to have got wrong.
        assertEquals(3, groups(catalogue, "Material cost lines").size)
        assertEquals(
            "the catalogue numbered a heading",
            emptyList<String>(),
            catalogue.blocks.filterIsInstance<HeadingBlock>()
                .filter { it.number.isNotEmpty() }
                .map { "${it.number} ${runText(it.runs)}" },
        )

        val dch = buildAs(schema, costingDraft(), "DCH_STANDARD")
        assertTrue(
            "the DCH standard left a group heading unnumbered",
            headingBlocks(dch, "Material cost lines").all { it.number.isNotEmpty() },
        )
    }

    @Test
    fun `no template drives a heading past the level the two writers can index`() {
        /*
         * A LEVEL PAST FOUR DOES NOT CRASH — IT COLLAPSES SILENTLY, WHICH IS WORSE.
         * `DocxWriter.emitHeading` reads `intArrayOf(320, 260, 220, 180)[block.level - 1]` with no
         * bounds check, but nothing ever reaches it out of range: `DocumentBuilder.heading` coerces
         * to 1..4 before the block is built, and `PdfWriter.blockHeading` coerces again. So a group
         * pushed to level 5 arrives at both writers as a level 4 — the same level as the RECORDS
         * inside it, sharing their heading counter. The document then numbers a group and the lines
         * under it in one sequence, and the contents page and the PDF's own navigation are built from
         * those numbers. No exception, no warning, a delivered file whose numbering is wrong.
         *
         * The grouping is the only thing in this builder that adds a heading level, and it adds it
         * under a collection heading whose own level differs between templates, so the cap has to
         * hold for all six. Rendering the .docx is half the assertion and not a formality: a block
         * list that looks right still has to survive the writer. This reads the bytes of the archive
         * the officer's Word opens.
         *
         * NOTE ON THE PDF. The on-device PDF cannot be rendered here at all — `PdfWriter` draws on
         * `android.graphics.pdf.PdfDocument`, and this source set runs against the mockable android
         * jar (`testOptions.unitTests.isReturnDefaultValues = true`), where `startPage` answers null
         * and `writeTo` writes nothing. There is no `androidTest` source set in this project to run
         * it in. What IS pinned for the PDF is its whole input — the block list, every heading level
         * and every heading number — plus the fact that `blockHeading` coerces to 1..4, so the level
         * the grouping introduces cannot reach the outline as anything the reader's sidebar refuses.
         */
        val schema = basicCostingSchema()
        val draft = costingDraft()
        for (template in REPORT_TEMPLATES) {
            val document = buildAs(schema, draft, template.id)
            assertEquals(
                "${template.id} emitted a heading level neither writer can index",
                emptyList<String>(),
                document.blocks.filterIsInstance<HeadingBlock>()
                    .filter { it.level !in 1..4 }
                    .map { "level ${it.level}: ${runText(it.runs)}" },
            )
            // The exact levels, not merely legal ones: stage 17 names four collections, so the
            // collection sits at H2 and its groups one below at H3. A group raised to the collection's
            // own level would read as a new collection and renumber the rest of the report; a group
            // pushed to H4 would put its records at H5 and take the .docx down with it.
            assertEquals(
                "${template.id} moved the collection heading",
                2,
                document.blocks.filterIsInstance<HeadingBlock>()
                    .single { runText(it.runs) == "Material cost lines" }.level,
            )
            assertTrue(
                "${template.id} did not put the groups one level below their collection",
                headingBlocks(document, "Material cost lines").all { it.level == 3 },
            )

            val xml = docxText(document)
            val section = xml.substring(
                xml.indexOf("Material cost lines"),
                xml.indexOf("Labour cost lines"),
            )
            val found = materialSection.map { needle ->
                val at = section.indexOf(needle)
                assertTrue("${template.id}: $needle never reached the material cost section", at >= 0)
                at
            }
            assertEquals("${template.id} printed the section out of order", found.sorted(), found)
        }
    }
}
