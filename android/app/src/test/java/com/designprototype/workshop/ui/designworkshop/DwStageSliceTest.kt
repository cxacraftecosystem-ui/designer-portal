package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.CoverBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.Run
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [stageBlockRange], [sliceStageBlocks] and [spliceStageBlocks] against the vectors the web's
 * `e2e/stage-slice-unit.spec.ts` reads — `shared/report-parity/stage-slice-vectors.json` — so the
 * two clients find a stage, and splice a live copy of it into the saved document, the same way.
 */
class DwStageSliceTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        repoFile("shared/report-parity/stage-slice-vectors.json").readText()
    ).jsonObject

    private fun block(spec: String): Block {
        val heading = Regex("^H(\\d):(.*)$").find(spec)
        return when {
            heading != null -> HeadingBlock(level = heading.groupValues[1].toInt(), runs = listOf(Run(heading.groupValues[2])))
            spec == "COVER" -> CoverBlock(title = "Cover")
            else -> ParagraphBlock(runs = listOf(Run(spec)))
        }
    }

    private fun spec(block: Block): String = when (block) {
        is HeadingBlock -> "H${block.level}:${block.runs.joinToString("") { it.text }}"
        is CoverBlock -> "COVER"
        is ParagraphBlock -> block.runs.joinToString("") { it.text }
        else -> error("unexpected $block")
    }

    private fun strings(array: JsonArray) = array.map { it.jsonPrimitive.content }

    @Test
    fun rangesMatchTheSharedVectors() {
        for (vector in vectors.getValue("range").jsonArray) {
            val v = vector.jsonObject
            val name = v.getValue("name").jsonPrimitive.content
            val blocks = strings(v.getValue("blocks").jsonArray)
            val expected = v.getValue("expected").let { if (it is JsonNull) null else it.jsonArray.map { n -> n.jsonPrimitive.int } }
            val title = v.getValue("title").jsonPrimitive.content
            val range = stageBlockRange(blocks.map(::block), title)
            assertEquals(name, expected, range?.let { listOf(it.first, it.last + 1) })
            assertEquals(
                name,
                expected?.let { blocks.subList(it[0], it[1]) },
                sliceStageBlocks(blocks.map(::block), title)?.map(::spec),
            )
        }
    }

    @Test
    fun splicesMatchTheSharedVectors() {
        for (vector in vectors.getValue("splice").jsonArray) {
            val v = vector.jsonObject
            val name = v.getValue("name").jsonPrimitive.content
            val expected = v.getValue("expected").let { if (it is JsonNull) null else strings(it.jsonArray) }
            val out = spliceStageBlocks(
                strings(v.getValue("base").jsonArray).map(::block),
                strings(v.getValue("live").jsonArray).map(::block),
                v.getValue("title").jsonPrimitive.content,
            )
            assertEquals(name, expected, out?.map(::spec))
        }
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from ${File(".").absolutePath}")
    }
}
