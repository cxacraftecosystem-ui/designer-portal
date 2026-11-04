package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.WorkshopDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwParentGroupParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun repoFile(vararg relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }

    private fun caseTable(): JsonObject = json.parseToJsonElement(
        repoFile(
            "src/test/resources/dw-parent-group-cases.json",
            "app/src/test/resources/dw-parent-group-cases.json",
            "android/app/src/test/resources/dw-parent-group-cases.json",
        ).readText()
    ).jsonObject

    private fun shippedRegistry(): SchemaResponse = json.decodeFromString(
        SchemaResponse.serializer(),
        repoFile(
            "src/main/assets/design-workshop-schema.json",
            "app/src/main/assets/design-workshop-schema.json",
            "android/app/src/main/assets/design-workshop-schema.json",
        ).readText()
    )

    private fun entityOf(schema: SchemaResponse, stageKey: String, entityKey: String): EntityDto =
        schema.stages.first { it.key == stageKey }.entities.first { it.key == entityKey }

    private fun draftRows(entityKey: String, rows: JsonArray, case: Int, role: String): List<DraftRow> =
        rows.mapIndexed { index, row ->
            DraftRow(id = "$entityKey#k$case$role$index", values = row.jsonObject.toMap())
        }

    private fun normalise(heading: String, parentTitle: String): String =
        heading.removePrefix("$parentTitle — ")

    private fun text(value: JsonElement?): String =
        (value as? JsonPrimitive)?.content.orEmpty()

    @Test
    fun `the handset groups a child collection exactly as the server does`() {
        val table = caseTable()
        val schema = shippedRegistry()
        val products = table.getValue("products").jsonArray
        val labelKeys = table.getValue("labelKeys").jsonObject
        val cases = table.getValue("cases").jsonArray
        val expected = table.getValue("expected").jsonArray
        assertEquals("the case table and the server's answers are different lengths",
            cases.size, expected.size)
        assertTrue("the case table is empty", cases.size >= 16)

        val diffs = ArrayList<String>()
        for (index in cases.indices) {
            val case = cases[index].jsonObject
            val name = text(case["name"])
            val stageKey = text(case["stage"])
            val entityKey = text(case["entity"])
            val parentStageKey = text(case["parentStage"])
            val parentEntityKey = text(case["parentEntity"])

            val childRows = draftRows(entityKey, case.getValue("children").jsonArray, index, "c")
            val parentRows = draftRows(parentEntityKey, case.getValue("parents").jsonArray, index, "p")
            val productRows = draftRows("finalProduct", products, index, "f")

            val byStage = LinkedHashMap<String, MutableList<DraftRow>>()
            byStage.getOrPut("FINAL_PROTOTYPE_DOCUMENTATION") { ArrayList() } += productRows
            byStage.getOrPut(parentStageKey) { ArrayList() } += parentRows
            byStage.getOrPut(stageKey) { ArrayList() } += childRows
            val draft = WorkshopDraft(
                workshopId = "parity",
                title = "Parity",
                stages = byStage.mapValues { (key, rows) -> StageDraft(stageId = key, rows = rows) },
            )

            val entity = entityOf(schema, stageKey, entityKey)
            val parentTitle = entityOf(schema, parentStageKey, parentEntityKey).title
            val refs = DwRefLabels(schema, draft)
            val groups = dwParentGroups(
                schema = schema,
                draft = draft,
                entity = entity,
                rows = childRows.map { it.values },
                parentHeading = { parent, values, ordinal -> rowHeading(parent, values, ordinal, refs) },
                refLabel = { refId -> refs.label(refId) },
            )

            val labelKey = text(labelKeys[entityKey])
            val actual: List<Pair<String, List<String>>>? = groups?.map { group ->
                normalise(group.heading, parentTitle) to group.rows.map { text(it[labelKey]) }
            }

            val server = expected[index].jsonObject
            assertEquals("the case table and the answers are out of step at $index",
                name, text(server["name"]))
            val want: List<Pair<String, List<String>>>? =
                (server["groups"] as? JsonArray)?.map { group ->
                    text(group.jsonObject["heading"]) to
                        group.jsonObject.getValue("rows").jsonArray.map { text(it) }
                }

            if (actual != want) {
                diffs += "  $name\n      server:  $want\n      handset: $actual"
            }
        }

        assertEquals(
            "the handset and the server disagree about ${diffs.size} of ${cases.size} cases:\n" +
                diffs.joinToString("\n"),
            0,
            diffs.size,
        )
    }
}
