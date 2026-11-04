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

class DwParentGroupTest {

    private val registryJson = Json { ignoreUnknownKeys = true }

    private fun text(key: String, label: String, role: String = "TABLE_COLUMN") =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = role)

    private fun ref(key: String, label: String, model: String, role: String) =
        FieldDto(key = key, label = label, type = "REF", refModel = model, reportRole = role)

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
                    row("costMaterialLine#r4", "_entryId" to "m4", "costSheetRef" to "",
                        "item" to "Zari thread", "amount" to "90"),
                    row("costMaterialLine#r5", "_entryId" to "m5",
                        "costSheetRef" to "cmsdeletedsheet0001",
                        "item" to "Lining cloth", "amount" to "60"),

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

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

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

    private fun groups(document: ReportDocument, heading: String) =
        under(document, heading) { row -> runText(row.first()) }

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

    @Test
    fun `material lines are printed under the cost sheet they belong to`() {
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
        assertEquals(
            listOf("Cost sheets — Sambalpuri stole", "Cost sheets — Ikat cushion cover"),
            groups(build(costingSchema(), costingDraft()), "Labour cost lines").map { it.first },
        )
    }

    @Test
    fun `every line that was recorded is still printed exactly once`() {
        val printed = groups(build(costingSchema(), costingDraft()), "Material cost lines")
            .flatMap { it.second }
        assertEquals(
            listOf("Cotton yarn", "Lining cloth", "Natural dye", "Tussar yarn", "Zari thread"),
            printed.sorted(),
        )
    }

    @Test
    fun `a line naming no cost sheet is printed under a heading that says so`() {
        val found = groups(build(costingSchema(), costingDraft()), "Material cost lines")
        assertEquals("No cost sheet recorded", found.last().first)
        assertEquals(listOf("Zari thread", "Lining cloth"), found.last().second.toList())
    }

    @Test
    fun `an orphan heading is not printed when every line names its sheet`() {
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
        val draft = costingDraft()
        val stage = draft.stages.getValue("COSTING_MARKET_LINKAGE")
        val unsynced = draft.copy(
            stages = draft.stages + ("COSTING_MARKET_LINKAGE" to stage.copy(
                rows = stage.rows.map { r ->
                    if (r.id != "costSheet#r1") r
                    else r.copy(values = r.values - "_entryId")
                }
            ))
        )
        val found = groups(build(costingSchema(), unsynced), "Material cost lines")
        assertEquals(
            listOf("Cost sheets — Ikat cushion cover", "No cost sheet recorded"),
            found.map { it.first },
        )
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
        val document = build(costingSchema(), costingDraft())
        assertEquals(emptyList<String>(), groups(document, "Buyer linkages").map { it.first })
        assertEquals(listOf(listOf("Tantuja")), tableRows(document, "Buyer linkages"))
        assertEquals(
            listOf(listOf("Sambalpuri stole", "Ikat cushion cover")),
            tableRows(document, "Cost sheets"),
        )
    }

    @Test
    fun `a group is headed by the same words as the sheet it belongs to`() {
        val document = build(costingSchema(), costingDraft())
        val sheets = tableRows(document, "Cost sheets").single()
        val names = groups(document, "Material cost lines").map { it.first }
        assertEquals(listOf("Sambalpuri stole", "Ikat cushion cover"), sheets)
        assertEquals(sheets, names.take(2).map { it.removePrefix("Cost sheets — ") })
        assertFalse("a group heading printed a raw record id: $names", names.any { it.contains("cs1") })
    }

    @Test
    fun `a sheet that names no product still heads its group readably`() {
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

    @Test
    fun `stage logs and material usage are grouped under their prototype`() {
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
        assertFalse(byName.getValue("Prototypes — Stole A").contains("Softer weft"))
        assertFalse(byName.getValue("Prototypes — Cushion B").contains("Widened border"))
    }

    @Test
    fun `an iteration whose prototype is missing is still printed`() {
        val draft = prototypeDraft()
        val orphaned = draft.copy(stages = draft.stages - "PROTOTYPE_DEVELOPMENT")
        val found = groupRows(build(prototypeSchema(), orphaned), "Prototype iteration and testing")
        assertEquals(listOf("No prototype recorded"), found.map { it.first })
        val printed = found.single().second.joinToString(" ")
        assertTrue(printed.contains("Widened border"))
        assertTrue(printed.contains("Softer weft"))
    }

    @Test
    fun `a group heading never outranks the collection it is part of and stays within level four`() {
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
        val document = build(costingSchema(), costingDraft())
        val depth = document.blocks.filterIsInstance<TocBlock>().firstOrNull()?.depth ?: 3
        assertEquals(3, depth)
        val listed = document.blocks.filterIsInstance<HeadingBlock>()
            .filter { it.level <= depth }
            .map { runText(it.runs) }
        assertTrue("the groups are missing from the contents: $listed",
            listed.containsAll(listOf("Cost sheets — Sambalpuri stole", "No cost sheet recorded")))

        val cards = build(proseCostingSchema(), costingDraft())
        val records = cards.blocks.filterIsInstance<HeadingBlock>()
            .filter { runText(it.runs).startsWith("Material cost lines — ") }
        assertTrue("no record headings to check", records.isNotEmpty())
        assertTrue("a record heading would flood the contents page",
            records.all { it.level > depth })
    }

    @Test
    fun `group headings are numbered beneath the collection they belong to`() {
        val document = build(costingSchema(), costingDraft())
        val collection = document.blocks.filterIsInstance<HeadingBlock>()
            .single { runText(it.runs) == "Material cost lines" }
        assertEquals(
            listOf("${collection.number}.1", "${collection.number}.2", "${collection.number}.3"),
            headingBlocks(document, "Material cost lines").map { it.number },
        )
        val labour = document.blocks.filterIsInstance<HeadingBlock>()
            .single { runText(it.runs) == "Labour cost lines" }
        val head = collection.number.substringBeforeLast(".")
        val last = collection.number.substringAfterLast(".").toInt()
        assertEquals("$head.${last + 1}", labour.number)
    }

    @Test
    fun `a card falls below its group rather than beside it`() {
        val document = build(proseCostingSchema(), costingDraft())
        val levels = document.blocks.filterIsInstance<HeadingBlock>()
            .dropWhile { runText(it.runs) != "Material cost lines" }
            .drop(1)
            .takeWhile { it.level > 2 }
            .map { it.level }
        assertEquals("expected a group then its records", listOf(3, 4, 4), levels.take(3))
    }

    private fun shippedRegistry(): SchemaResponse {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "src/main/assets/design-workshop-schema.json"),
                File(dir, "app/src/main/assets/design-workshop-schema.json"),
                File(dir, "android/app/src/main/assets/design-workshop-schema.json"),
            )) {
                if (candidate.isFile) {
                    return registryJson
                        .decodeFromString(SchemaResponse.serializer(), candidate.readText())
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("design-workshop-schema.json not found from ${File(".").absolutePath}")
    }

    private fun filler(field: FieldDto): JsonElement? = when (field.type) {
        "IMAGE", "IMAGE_LIST", "FILE", "AUDIO", "VIDEO", "GEO", "RICH_TEXT" -> null
        "INT", "DECIMAL", "MONEY", "PERCENT" -> JsonPrimitive(7)
        "BOOL" -> JsonPrimitive(true)
        "DATE" -> JsonPrimitive("2026-02-03")
        "TIME" -> JsonPrimitive("10:30")
        "ENUM" -> field.options.firstOrNull()?.let { JsonPrimitive(it.value) }
        "MULTI_ENUM", "TAGS" -> null
        "REF" -> JsonPrimitive("e0")
        else -> JsonPrimitive("${field.key} value")
    }

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
        assertTrue("only $compared parent-free stages produced any blocks", compared >= 15)
        assertNotEquals(before, after)
        assertTrue(before.filterIsInstance<TableBlock>().size > 10)

        val bookmarks = after.filterIsInstance<HeadingBlock>().map { it.bookmark }
        assertEquals(
            "two headings of the grouped document share a bookmark: " +
                bookmarks.groupingBy { it }.eachCount().filterValues { it > 1 }.keys,
            bookmarks.size,
            bookmarks.toSet().size,
        )
    }

    private fun anonymised(blocks: List<Block>): List<Block> =
        blocks.map { if (it is HeadingBlock) it.copy(bookmark = "") else it }

    private fun sectionOf(blocks: List<Block>, title: String): List<Block> {
        val start = blocks.indexOfFirst { it is HeadingBlock && it.level == 1 && runText(it.runs) == title }
        if (start < 0) return emptyList()
        val rest = blocks.drop(start + 1)
        return listOf(blocks[start]) + rest.takeWhile { !(it is HeadingBlock && it.level == 1) }
    }

    @Test
    fun `every collection of the shipped registry keeps every one of its rows`() {
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
        assertEquals(40, declined)
    }

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
        assertTrue(section.contains("w:val=\"Heading3\""))
        assertTrue(section.contains("<w:outlineLvl w:val=\"2\"/>"))
    }

    @Test
    fun `the docx numbers the group headings in sequence with the rest of the report`() {
        val document = build(costingSchema(), costingDraft())
        val xml = docxText(document)
        val numbers = headingBlocks(document, "Material cost lines").map { it.number }
        for (number in numbers) {
            assertTrue("the .docx lost the number $number", xml.contains(">$number. <"))
        }
    }

    private fun withoutParents(schema: SchemaResponse) = schema.copy(
        stages = schema.stages.map { stage ->
            stage.copy(entities = stage.entities.map { it.copy(parent = "") })
        }
    )

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

    private val materialSection = listOf(
        "Sambalpuri stole", "Tussar yarn", "Natural dye",
        "Ikat cushion cover", "Cotton yarn",
        "No cost sheet recorded", "Zari thread", "Lining cloth",
    )

    @Test
    fun `every template the picker offers groups the lines under their own sheet`() {
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
        val schema = basicCostingSchema()
        val catalogue = buildAs(schema, costingDraft(), "PHOTO_CATALOGUE")
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
