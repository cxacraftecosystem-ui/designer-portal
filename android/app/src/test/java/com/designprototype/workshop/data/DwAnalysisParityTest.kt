package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwAnalysisParityTest {

    private val json = Json { prettyPrint = false }

    private val schemaJson = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): JsonElement {
        val stream = javaClass.getResourceAsStream("/$name")
            ?: error("$name is missing from the test resources; the parity table is the test.")
        return json.parseToJsonElement(stream.reader(Charsets.UTF_8).use { it.readText() })
    }

    private fun computeAll(table: JsonObject): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()

        for (case in table.getValue("market").jsonArray) {
            val row = case.jsonObject
            out["market/" + row.getValue("name").content()] = DwMarketAnalysis.payload(
                DwMarketAnalysis.analyse(
                    responses = row.rows("responses"),
                    competitors = row.rows("competitors"),
                    bands = row.rows("bands"),
                    swot = row.rows("swot"),
                )
            )
        }

        for (case in table.getValue("competitorPositions").jsonArray) {
            val row = case.jsonObject
            val observations = row.getValue("observations").jsonArray.map { entry ->
                val o = entry.jsonObject
                DwPriceObservation(
                    amount = o.getValue("amount").jsonPrimitiveContent().toDouble(),
                    category = o.getValue("category").content(),
                    source = o.getValue("source").content(),
                    group = o.getValue("group").content(),
                    label = o.getValue("label").content(),
                )
            }
            val placed = DwMarketAnalysis.positionCompetitors(row.rows("competitors"), observations)
            out["competitorPositions/" + row.getValue("name").content()] = JsonArray(
                placed.map { competitor ->
                    buildJsonObject {
                        put("name", competitor.name)
                        put("seller", competitor.seller)
                        put("category", competitor.category)
                        put("price", competitor.price)
                        put(
                            "percentile",
                            competitor.percentile?.let { JsonPrimitive(DwPy.round(it, 4)) } ?: JsonNull
                        )
                        put(
                            "versusMedian",
                            competitor.versusMedian?.let { JsonPrimitive(DwPy.round(it, 2)) } ?: JsonNull
                        )
                        put("note", competitor.note)
                    }
                }
            )
        }

        for (case in table.getValue("cost").jsonArray) {
            val row = case.jsonObject
            val labels = row.getValue("labels").jsonObject.mapValues { it.value.content() }
            out["cost/" + row.getValue("name").content()] = DwCostIntegrity.payload(
                DwCostIntegrity.analyse(
                    sheets = row.rows("sheets"),
                    materialLines = row.rows("materialLines"),
                    labourLines = row.rows("labourLines"),
                    labels = labels,
                )
            )
        }
        return out
    }

    @Test
    fun `every case matches the payload the Python produced for it`() {
        val table = resource("dw-analysis-cases.json").jsonObject
        val golden = resource("dw-analysis-expected.json").jsonObject
        val computed = computeAll(table)

        runCatching {
            val dump = File("build/parity/kotlin-analysis.json").absoluteFile
            dump.parentFile?.mkdirs()
            dump.writeText(
                json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    JsonObject(computed.toSortedMap())
                ),
                Charsets.UTF_8,
            )
            println("parity dump: $dump")
        }

        assertEquals(
            "the case table and the golden must describe the same cases",
            golden.keys.sorted(), computed.keys.sorted()
        )

        val problems = ArrayList<String>()
        val failedCases = LinkedHashSet<String>()
        for ((key, expected) in golden) {
            val before = problems.size
            diff(key, expected, computed.getValue(key), problems)
            if (problems.size != before) failedCases.add(key)
        }

        println("parity: ${computed.size} cases compared, ${problems.size} differing leaves")
        assertTrue(
            "PARITY BROKEN — ${problems.size} differing leaf value(s) across " +
                "${failedCases.size} of ${computed.size} cases.\n" +
                problems.take(40).joinToString("\n"),
            problems.isEmpty()
        )
    }

    @Test
    fun `the six cost heads are the ones the shipped registry declares`() {
        val asset = File("src/main/assets/design-workshop-schema.json")
        if (!asset.exists()) return
        val schema = schemaJson
            .decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
        val declared = schema.stages
            .firstOrNull { it.key == "COSTING_MARKET_LINKAGE" }
            ?.entities?.firstOrNull { it.key == "costSheet" }
            ?.field("totalCost")
            ?.derivedFrom
        assertEquals(DwCostIntegrity.COST_HEADS, declared)
    }

    private fun JsonElement.content(): String = (this as JsonPrimitive).content

    private fun JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content

    private fun JsonObject.rows(key: String): List<DwDataRow> =
        getValue(key).jsonArray.map { it.jsonObject }

    private fun diff(path: String, expected: JsonElement, actual: JsonElement, out: MutableList<String>) {
        if (expected is JsonObject && actual is JsonObject) {
            for (key in (expected.keys + actual.keys).sorted()) {
                val left = expected[key]
                val right = actual[key]
                when {
                    left == null -> out.add("$path/$key: python <absent>, kotlin $right")
                    right == null -> out.add("$path/$key: python $left, kotlin <absent>")
                    else -> diff("$path/$key", left, right, out)
                }
            }
            return
        }
        if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) {
                out.add("$path: python has ${expected.size} item(s), kotlin ${actual.size}")
                return
            }
            expected.forEachIndexed { index, item -> diff("$path[$index]", item, actual[index], out) }
            return
        }
        if (expected == JsonNull || actual == JsonNull) {
            if (expected != actual) out.add("$path: python $expected, kotlin $actual")
            return
        }
        val left = expected as? JsonPrimitive
        val right = actual as? JsonPrimitive
        if (left == null || right == null) {
            out.add("$path: python $expected, kotlin $actual")
            return
        }
        if (left.isString != right.isString) {
            out.add("$path: python ${if (left.isString) "string" else "literal"} ${left.content}, " +
                "kotlin ${if (right.isString) "string" else "literal"} ${right.content}")
            return
        }
        if (left.isString) {
            if (left.content != right.content) {
                out.add("$path: python ${quote(left.content)}, kotlin ${quote(right.content)}")
            }
            return
        }
        if (left.content == "true" || left.content == "false" ||
            right.content == "true" || right.content == "false"
        ) {
            if (left.content != right.content) {
                out.add("$path: python ${left.content}, kotlin ${right.content}")
            }
            return
        }
        val leftNumber = left.content.toDouble()
        val rightNumber = right.content.toDouble()
        if (java.lang.Double.compare(leftNumber, rightNumber) != 0) {
            out.add("$path: python ${left.content}, kotlin ${right.content}")
            return
        }
        if (isFractional(left.content) != isFractional(right.content)) {
            out.add("$path: python ${left.content} (${kind(left.content)}), " +
                "kotlin ${right.content} (${kind(right.content)})")
        }
    }

    private fun isFractional(literal: String): Boolean =
        literal.contains('.') || literal.contains('e') || literal.contains('E')

    private fun kind(literal: String): String = if (isFractional(literal)) "float" else "int"

    private fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            if (ch.code in 32..126) append(ch) else append("\\u%04x".format(ch.code))
        }
        append('"')
    }
}
