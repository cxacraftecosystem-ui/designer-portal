package com.designprototype.workshop.report

import com.designprototype.workshop.data.DwTranscriptItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone's transcript annexure, held block for block to the SERVER's (DA-29).
 *
 * `shared/report-parity/expected.json` is written by `backend/tools/report_offline_parity.py`, which
 * runs `report_annexures.append_transcript_annexure` — the server's own code — over the transcripts in
 * `shared/report-parity/workshop.json`; `tests/test_report_offline_parity.py` fails the backend build
 * when that file is stale. So the slice compared here is not a copy somebody typed: it is what the
 * office's report prints for these recordings today, and [appendTranscriptAnnexure] has to print the
 * same headings, table, notes and paragraphs in the same order.
 *
 * Heading NUMBERS are compared below their first component: the server's annexure sits at section 28
 * of a whole report and this one is built alone, so "28.1" and "1.1" are the same position.
 */
class ReportTranscriptParityTest {

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

    private fun shared(name: String): JsonObject = Json.parseToJsonElement(
        repoFile("shared/report-parity/$name", "../shared/report-parity/$name", "../../shared/report-parity/$name")
            .readText(Charsets.UTF_8)
    ).jsonObject

    private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

    /** The fixture's transcripts, exactly as the tool hands them to `TranscriptItem(**t)`. */
    private fun fixtureItems(): List<DwTranscriptItem> =
        shared("workshop.json")["sources"]!!.jsonObject["transcripts"]!!.jsonArray.map { raw ->
            val t = raw.jsonObject
            DwTranscriptItem(
                mediaId = t["media_id"].str(),
                stageKey = t["stage_key"].str(),
                stageNumber = t["stage_number"]?.jsonPrimitive?.intOrNull ?: 0,
                stageTitle = t["stage_title"].str(),
                entityKey = t["entity_key"].str(),
                fieldKey = t["field_key"].str(),
                fieldLabel = t["field_label"].str(),
                filename = t["filename"].str(),
                recordedAt = t["recorded_at"].str(),
                durationSeconds = (t["duration_seconds"] as? JsonPrimitive)?.intOrNull,
                status = t["status"].str(),
                text = t["text"].str(),
            )
        }

    /** The server's annexure out of one case: its page break up to the next page break or chapter. */
    private fun expectedSlice(case: String): List<JsonObject> {
        val blocks = shared("expected.json")["cases"]!!.jsonObject[case]!!.jsonObject["blocks"]!!.jsonArray
            .map { it.jsonObject }
        val head = blocks.indexOfFirst {
            it["type"].str() == "HEADING" && it["level"]?.jsonPrimitive?.intOrNull == 1 &&
                it["runs"]!!.jsonArray.joinToString("") { r -> r.jsonObject["text"].str() } ==
                TRANSCRIPT_DEFAULT_HEADING
        }
        assertTrue("case $case carries no transcript annexure", head > 0)
        assertEquals("PAGEBREAK", blocks[head - 1]["type"].str())
        var end = head + 1
        while (end < blocks.size) {
            val b = blocks[end]
            if (b["type"].str() == "PAGEBREAK") break
            if (b["type"].str() == "HEADING" && b["level"]?.jsonPrimitive?.intOrNull == 1) break
            end++
        }
        return blocks.subList(head - 1, end)
    }

    // ── one comparable shape for both sides ─────────────────────────────────────────────────────

    private fun plain(e: JsonElement?): Any? = when (e) {
        null, JsonNull -> null
        is JsonObject -> e.mapValues { plain(it.value) }
        is JsonArray -> e.map { plain(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.doubleOrNull
        }
    }

    private fun tail(number: String): String = number.substringAfter('.', "")

    private fun expectedShape(block: JsonObject): Any? {
        val map = (plain(block) as Map<*, *>).toMutableMap()
        map.remove("bookmark")
        map["number"]?.let { map["number"] = tail(it as String) }
        return map
    }

    private fun run(r: Run): Map<String, Any?> = mapOf(
        "text" to r.text, "bold" to r.bold, "italic" to r.italic, "underline" to r.underline,
        "strike" to r.strike, "script" to r.script.name, "color" to r.color,
        "superscript" to r.superscript, "subscript" to r.subscript, "highlight" to r.highlight,
    )

    private fun runs(rs: List<Run>) = rs.map(::run)

    private fun actualShape(block: Block): Any? = when (block) {
        is PageBreakBlock -> mapOf("type" to "PAGEBREAK")
        is HeadingBlock -> mapOf(
            "type" to "HEADING", "level" to block.level.toDouble(), "runs" to runs(block.runs),
            "number" to tail(block.number),
        )
        is ParagraphBlock -> mapOf(
            "type" to "PARAGRAPH", "runs" to runs(block.runs), "style" to block.style.name,
            "align" to block.align.name,
        )
        is SpacerBlock -> mapOf("type" to "SPACER", "height_pct" to block.heightPct.toDouble())
        is TableBlock -> mapOf(
            "type" to "TABLE",
            "columns" to block.columns.map {
                mapOf(
                    "header" to it.header, "width_pct" to it.widthPct.toDouble(),
                    "align" to it.align.name, "numeric" to it.numeric,
                )
            },
            "rows" to block.rows.map { row -> row.map { cell -> runs(cell) } },
            "caption" to block.caption,
            "total_row" to block.totalRow?.map { cell -> runs(cell) },
            "zebra" to block.zebra,
        )
        else -> mapOf("type" to block::class.simpleName)
    }

    private fun built(): List<Block> {
        val doc = DocumentBuilder(meta = ReportMeta(title = ""))
        appendTranscriptAnnexure(
            doc, fixtureItems(),
            heading = TRANSCRIPT_ANNEXURE.heading,
            numbered = true,
            pageBreakBefore = TRANSCRIPT_ANNEXURE.pageBreakBefore,
        )
        return doc.build().blocks
    }

    @Test
    fun `the annexure is the server's, block for block, in both cases that carry it`() {
        val mine = built().map(::actualShape)
        for (case in listOf("dch-file-with-annexures", "agency")) {
            val theirs = expectedSlice(case).map(::expectedShape)
            assertEquals("block count differs from the server's for $case", theirs.size, mine.size)
            theirs.indices.forEach { i ->
                assertEquals("block $i of $case differs from the server's", theirs[i], mine[i])
            }
        }
    }

    @Test
    fun `the fixture reaches the branches the port could drift on`() {
        // A fixture that stopped exercising these would let the port diverge there unseen.
        val blocks = built()
        val text = blocks.joinToString("\n") { b ->
            when (b) {
                is HeadingBlock -> runsText(b.runs)
                is ParagraphBlock -> runsText(b.runs)
                is TableBlock -> b.rows.joinToString("\n") { r -> r.joinToString(" | ") { runsText(it) } }
                else -> ""
            }
        }
        assertTrue("an hour-long recording", text.contains("1 h 02 min"))
        assertTrue("a word count past a thousand, grouped", Regex("\\| 1,\\d{3}").containsMatchIn(text))
        assertTrue("a label from the filename", text.contains("walkthrough.m4a"))
        assertTrue("a loose label folded and case-folded to two voices", text.contains("45 s · 2 speakers"))
        val speakerNotes = blocks.count { it is ParagraphBlock && runsText(it.runs) == TRANSCRIPT_SPEAKER_NOTE }
        assertEquals("one caution per labelled transcript, none for the solo walkthrough", 2, speakerNotes)
        assertTrue("an unlabelled solo voice is one speaker", text.contains("1 speaker ·"))
    }

    @Test
    fun `the warnings are the server's sentence for the fixture`() {
        val server = shared("workshop.json")["sources"]!!.jsonObject["warnings"]!!.jsonObject["transcripts"]!!
            .jsonArray.map { it.str() }
        assertEquals(server, transcriptAnnexureWarnings(fixtureItems()))
    }

    @Test
    fun `a runaway transcript is shortened and says so`() {
        val text = (1..(MAX_PARAGRAPHS_PER_TRANSCRIPT + 5)).joinToString("\n") { "line $it" }
        val blocks = transcriptBodyBlocks(text)
        assertEquals(MAX_PARAGRAPHS_PER_TRANSCRIPT + 1, blocks.size)
        val note = blocks.last() as ParagraphBlock
        assertEquals(ParaStyle.NOTE, note.style)
        assertTrue(runsText(note.runs).startsWith("[Transcript shortened to the first 1200 paragraphs."))
    }

    @Test
    fun `nothing transcribed prints nothing at all, not even the page break`() {
        val doc = DocumentBuilder(meta = ReportMeta(title = ""))
        val printed = appendTranscriptAnnexure(doc, listOf(DwTranscriptItem(mediaId = "m1", status = "QUEUED")))
        assertEquals(0, printed)
        assertEquals(emptyList<Block>(), doc.build().blocks)
    }

    @Test
    fun `durations read as the server writes them`() {
        assertEquals("", transcriptDurationText(null))
        assertEquals("", transcriptDurationText(0))
        assertEquals("45 s", transcriptDurationText(45))
        assertEquals("12 min 34 s", transcriptDurationText(754))
        assertEquals("1 min 05 s", transcriptDurationText(65))
        assertEquals("1 h 02 min", transcriptDurationText(3725))
    }
}
