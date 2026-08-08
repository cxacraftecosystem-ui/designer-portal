package com.designprototype.workshop.report

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.ui.designworkshop.buildWorkshopDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * How much of the workshop is actually IN the .docx the handset writes.
 *
 * ── THE DEFECT THIS EXISTS TO MAKE IMPOSSIBLE ────────────────────────────────────────────────────
 *
 * A report exported on a Galaxy M32 was pulled off the device and opened. It was a valid OOXML
 * package. Every part parsed, every relationship resolved, the cover page carried the right craft
 * and the right cluster, the running foot numbered the pages — and the whole document was TEN
 * paragraphs long, because the report screen rendered from a draft whose stages had never been
 * downloaded. The officer standing at the close of the workshop was handed a cover sheet.
 *
 * THAT TEN IS REPRODUCED HERE EXACTLY. Against the shipped registry an empty draft comes to 10
 * block-level elements and a filled one to 596 (472 paragraphs, 124 tables) — measured on
 * 2026-08-09, not estimated. The device's file was the floor and nothing above it.
 *
 * NOTHING IN THE SUITE COULD SEE IT. `DocxMediaStreamingTest` proves the writer does not hold
 * photographs on the heap; `ReportDocumentTest` and `ReportTemplateDocumentTest` prove
 * `buildWorkshopDocument` puts the right BLOCKS in a [ReportDocument]. Both are true of a document
 * built from an empty draft. The one quantity nobody asserted is the one a reader notices first:
 * how much of the file there is. This test asserts it on the far side of [renderDocx], from the
 * bytes, so the claim covers the builder AND the writer AND the zip — the whole of what
 * `ReportExport.exportDocx` produces.
 *
 * ── WHY IT COUNTS AND DOES NOT READ ──────────────────────────────────────────────────────────────
 *
 * Every assertion below is a COUNT or the PRESENCE OF A SECTION, and not one of them names a
 * sentence the report prints. That is deliberate and it is what keeps this test alive: a test
 * pinned to wording fails on the next copy edit, gets "fixed" by pasting in the new wording, and is
 * deleted the third time. The words are already covered by `ReportDocumentTest`, which asserts that
 * the prose a designer typed survives into the blocks. What this file adds is the volume, which is
 * exactly the axis the shipped defect moved along and the only one it moved along.
 *
 * The thresholds are therefore deliberately loose. They are not a pin on the current template; they
 * are the distance between a report and a cover sheet, and any edit that legitimately halves the
 * length of the DCH standard report still passes.
 *
 * ── WHAT IT BUILDS FROM ──────────────────────────────────────────────────────────────────────────
 *
 * The SHIPPED REGISTRY (`assets/design-workshop-schema.json`) rather than a hand-built fixture,
 * following `DwParentGroupTest.shippedRegistry`. A miniature schema of three stages could be filled
 * completely and still produce a short document, so it could not tell the two cases apart; the
 * claim being made is about the twenty-two stages a designer exports tonight.
 *
 * ONE FIGURE IS ABSENT HERE AND THAT IS THE JVM, NOT THE CODE. The locator map rasterises from
 * boundary geometry installed by `installReportBoundaryAssets`, which needs a `Context`; with no
 * source installed `BoundaryAssets.indiaRings()` is empty, `renderMapPng` returns null and
 * `DocxWriter.emitMap` records `map:india` as a dropped image instead of drawing it — the same
 * documented path a missing asset takes on a handset. Nothing below counts on the map, so the
 * populated document is measured one figure SHORT of what the phone writes, which makes the
 * assertions conservative rather than optimistic. The infographics are NOT affected: a native
 * `c:chart` needs no bitmap, so they are in the package and the last test below insists on it.
 */
class DocxContentTest {

    // ── The registry the APK ships ───────────────────────────────────────────────────────────────

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
    private val registryJson = Json { ignoreUnknownKeys = true }

    /**
     * The registry as built into the APK, located by walking up from the test's working directory.
     *
     * Gradle runs unit tests with the module directory as the working directory, but that is a
     * default rather than a contract, and a test that silently could not find the registry would be
     * a test that proves nothing at all.
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
                    return registryJson
                        .decodeFromString(SchemaResponse.serializer(), candidate.readText())
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("design-workshop-schema.json not found from ${File(".").absolutePath}")
    }

    // ── One workshop, filled ─────────────────────────────────────────────────────────────────────

    /** `{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":…}]}]}` — the shape `rich_text.to_json` writes. */
    private fun richDoc(text: String): JsonElement = buildJsonObject {
        putJsonArray("blocks") {
            add(
                buildJsonObject {
                    put("kind", "PARAGRAPH")
                    putJsonArray("spans") { add(buildJsonObject { put("text", text) }) }
                }
            )
        }
    }

    /**
     * One deterministic answer per field type, so a stage is filled without this test naming its
     * schema — the same shape as `DwParentGroupTest.filler`, widened to the two types that carry
     * most of a report's bulk.
     *
     * RICH_TEXT AND IMAGE_LIST ARE FILLED HERE AND ARE NOT IN THAT HELPER. The registry declares 98
     * RICH_TEXT fields — every narrative and every bulleted list in the document — and leaving them
     * blank would measure a report with its prose removed, which is most of the thing being
     * measured. The image fields are what put a `w:drawing` and a `word/media/` part in the package.
     */
    private fun filler(field: FieldDto): JsonElement? = when (field.type) {
        "RICH_TEXT" -> richDoc("Recorded during the ${field.label.lowercase()} session.")
        "IMAGE" -> JsonPrimitive("media-${field.key}")
        "IMAGE_LIST" -> buildJsonArray {
            add(JsonPrimitive("media-${field.key}-1"))
            add(JsonPrimitive("media-${field.key}-2"))
        }
        // A FILE, an AUDIO, a VIDEO or a GEO has no printed form this test measures, and a
        // half-shaped value for one of them would be measuring the placeholder rather than the
        // report.
        "FILE", "AUDIO", "VIDEO", "GEO" -> null
        "INT", "DECIMAL", "MONEY", "PERCENT" -> JsonPrimitive(7)
        "BOOL" -> JsonPrimitive(true)
        "DATE" -> JsonPrimitive("2026-02-03")
        "TIME" -> JsonPrimitive("10:30")
        "ENUM" -> field.options.firstOrNull()?.let { JsonPrimitive(it.value) }
        "MULTI_ENUM" -> field.options.firstOrNull()?.let {
            buildJsonArray { add(JsonPrimitive(it.value)) }
        }
        // TAGS is free text and carries no options, so it cannot be filled from the registry. The
        // literal matters in exactly one place: stage 20's `excludedStages` is a TAGS field, and a
        // value that happened to be a real stage key would silently delete a section from the
        // document this test is measuring. "recorded" is not a stage key and never will be.
        "TAGS" -> buildJsonArray { add(JsonPrimitive("recorded")) }
        // The id of the first row of every collection, so the references resolve to a named record
        // rather than being blanked as orphans.
        "REF" -> JsonPrimitive("e0")
        else -> JsonPrimitive("${field.label} recorded")
    }

    /** Every stage of the shipped registry answered, three rows in every collection. */
    private fun populatedDraft(schema: SchemaResponse) = WorkshopDraft(
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

    /**
     * The draft the handset actually had when it wrote the ten-paragraph file: a workshop that
     * exists, is named, and whose stages have never been downloaded.
     *
     * NOT `null`, which would be a different and easier case. A null draft is "no workshop"; this is
     * the one that produced the shipped defect — a real workshop whose answers are on the server and
     * not on this device.
     */
    private fun emptyDraft() = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = emptyMap(),
    )

    // ── Building the package ─────────────────────────────────────────────────────────────────────

    /**
     * A PNG header `probeImageSize` reads, with padding standing in for pixels.
     *
     * Hand-built rather than loaded, exactly as `DocxMediaStreamingTest.fakePng` is: `pngSize` reads
     * the magic and then the IHDR width and height at bytes 16 and 20 and looks no further, so this
     * is a complete input for it and the size it reports is stated here in the test that relies on it.
     */
    private val fakePng: ByteArray = ByteArray(24 + 64).also { out ->
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(out)
        out[11] = 13
        "IHDR".forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        fun be32(at: Int, value: Int) {
            out[at] = (value ushr 24).toByte()
            out[at + 1] = (value ushr 16).toByte()
            out[at + 2] = (value ushr 8).toByte()
            out[at + 3] = value.toByte()
        }
        be32(16, 1600)
        be32(20, 1200)
    }

    /**
     * The document the report screen builds, through the SAME internal entry point its export
     * button reaches (`ReportScreen.export` → `buildWorkshopDocument`, ReportScreen.kt:235).
     */
    private fun documentOf(schema: SchemaResponse, draft: WorkshopDraft): ReportDocument =
        buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = "DCH_STANDARD",
            warnings = emptyList(),
            accent = "",
            // Every media id resolves, so the photograph path is exercised rather than skipped.
            imageFor = { id -> ImageRef(source = "/data/$id.png", widthPx = 1600, heightPx = 1200) },
            // Supplied so two runs of this test produce the same document.
            generatedAt = "2026-03-04T09:30:00Z",
        )

    /**
     * The .docx bytes, through the SAME function `ReportExport.exportDocx` calls
     * (ReportExport.kt:121 → DocxWriter.kt:2200).
     *
     * Going through [renderDocx] rather than constructing a [DocxWriter] here is the point: what is
     * being measured is the file the export writes, not an intermediate the export happens to use.
     */
    private fun docxOf(document: ReportDocument): ByteArray {
        val out = ByteArrayOutputStream()
        renderDocx(document, { fakePng }, out)
        return out.toByteArray()
    }

    private fun entriesOf(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    private fun documentXmlOf(bytes: ByteArray): String =
        String(
            requireNotNull(entriesOf(bytes)["word/document.xml"]) {
                "the package has no word/document.xml, which is not a .docx at all"
            },
            Charsets.UTF_8,
        )

    // ── Counting what a reader would count ───────────────────────────────────────────────────────

    /**
     * The tag names of the DIRECT children of `w:body` — the document's block-level elements.
     *
     * THIS IS THE NUMBER THE DEFECT REPORT QUOTED. "Ten paragraphs" is what a reader of the file
     * sees and what `python-docx` reports for `document.paragraphs`: top-level `w:p` and `w:tbl`,
     * NOT the paragraphs nested inside table cells. Counting `"<w:p"` across the whole part would
     * count every cell of every table and would report a cover-only document with one 10-row table
     * as longer than a twenty-page report with none, which is the wrong way round.
     *
     * Scanned rather than parsed with a DOM. The writer's output has no comments, no CDATA and no
     * processing instruction inside the body, and [esc] escapes `<` and `>` in every text node and
     * every attribute value it writes, so "the next `>` ends the tag" is exact here. A DOM parser
     * would be resolved against the mockable android.jar on this classpath rather than against the
     * JDK, which is a dependency this file does not need to take on to count children.
     */
    private fun bodyChildren(documentXml: String): List<String> {
        val open = documentXml.indexOf("<w:body>")
        require(open >= 0) { "the document part has no <w:body>" }
        var i = open + "<w:body>".length
        var depth = 0
        val out = ArrayList<String>()
        while (i < documentXml.length) {
            val start = documentXml.indexOf('<', i)
            if (start < 0) break
            val end = documentXml.indexOf('>', start)
            require(end > start) { "unterminated tag at offset $start" }
            val tag = documentXml.substring(start + 1, end)
            i = end + 1
            when {
                // The first close tag seen at depth 0 is </w:body> and ends the walk.
                tag.startsWith("/") -> if (depth == 0) break else depth -= 1
                tag.endsWith("/") -> if (depth == 0) out.add(tagName(tag))
                else -> {
                    if (depth == 0) out.add(tagName(tag))
                    depth += 1
                }
            }
        }
        // `w:sectPr` is the section's page setup, not a block a reader can see. It is present
        // exactly once in every document this writer produces, so counting it would add the same 1
        // to both sides and describe neither.
        return out.filter { it != "w:sectPr" }
    }

    private fun tagName(tag: String): String = tag.trimEnd('/').trim().substringBefore(' ')

    /** How many section headings the file carries, at any level. */
    private fun headingCount(documentXml: String): Int =
        Regex("<w:pStyle w:val=\"Heading[1-4]\"/>").findAll(documentXml).count()

    // ── The two documents ────────────────────────────────────────────────────────────────────────

    /**
     * The floor a cover page and a contents page put under EVERY export, however empty the workshop.
     * Measured at 10 — the same 10 the handset's file had.
     *
     * Stated as a CAP rather than an equality: the cover is a handful of centred paragraphs plus a
     * table of whatever stage 1 answered, and pinning its exact length would make this a test of the
     * cover's typography that fails when somebody adds a letterhead line. What must stay true is
     * that the floor is SMALL — a file this size is a cover sheet, and the moment a populated
     * workshop produces one, this test is the thing that says so.
     */
    private val coverFloorCap = 24

    /**
     * What a real workshop must clear. Twenty-two stages, forty-three entities, three rows in every
     * collection and ninety-eight narratives cannot come to fewer blocks than this without something
     * having been dropped wholesale; the shipped registry measures 596, so this leaves room for a
     * template edit that legitimately halves the report and still refuses a cover sheet.
     */
    private val populatedFloor = 200

    @Test
    fun `a populated workshop produces a document far above the cover-page floor`() {
        val schema = shippedRegistry()
        val blocks = bodyChildren(documentXmlOf(docxOf(documentOf(schema, populatedDraft(schema)))))

        assertTrue(
            "the .docx of a workshop with every one of its ${schema.stages.size} stages answered " +
                "carried only ${blocks.size} block-level elements. That is the shipped defect: a " +
                "valid OOXML package, the right cover, and no report under it.",
            blocks.size >= populatedFloor,
        )
        assertTrue(
            "the file carries no tables at all, so every roster, cost sheet and survey in the " +
                "record printed as nothing: ${blocks.groupingBy { it }.eachCount()}",
            blocks.count { it == "w:tbl" } >= 5,
        )
    }

    @Test
    fun `an empty draft produces the cover-page floor, and the difference is the whole report`() {
        val schema = shippedRegistry()
        val empty = bodyChildren(documentXmlOf(docxOf(documentOf(schema, emptyDraft()))))
        val populated = bodyChildren(documentXmlOf(docxOf(documentOf(schema, populatedDraft(schema)))))

        // THE HANDSET'S FILE, REPRODUCED. A workshop whose stages were never downloaded still gets
        // its cover and its contents page — which is exactly why the defect survived inspection.
        assertTrue(
            "an empty draft produced ${empty.size} block-level elements, which is no longer a cover " +
                "sheet. Either the cover grew a great deal or a section is now printing on a " +
                "workshop that has nothing in it.",
            empty.size <= coverFloorCap,
        )
        // The claim in one line: what the stages contribute is not a detail of the document, it IS
        // the document. A ratio rather than a difference so the assertion does not need re-tuning
        // when the cover gains a line.
        assertTrue(
            "the whole of a filled workshop added only ${populated.size - empty.size} blocks to the " +
                "${empty.size} an empty one produces. The report screen is rendering from stages it " +
                "does not have.",
            populated.size >= empty.size * 8,
        )
    }

    @Test
    fun `the sections a submitted report is made of are all in the package`() {
        val schema = shippedRegistry()
        val bytes = docxOf(documentOf(schema, populatedDraft(schema)))
        val xml = documentXmlOf(bytes)
        val entries = entriesOf(bytes)

        // Section presence, by structure. Not one of these names a sentence the report prints.
        assertTrue(
            "the file has only ${headingCount(xml)} section headings, so most of the twenty-two " +
                "stages printed nothing at all",
            headingCount(xml) >= 15,
        )
        assertTrue(
            "the contents page carries no TOC field, so a reader opening the file gets a heading " +
                "over a blank page",
            xml.contains("<w:instrText xml:space=\"preserve\"> TOC "),
        )
        assertTrue(
            "no photograph reached the document body, so every gallery in the record printed as a " +
                "caption with nothing above it",
            xml.contains("<w:drawing>"),
        )
        assertTrue(
            "the package embeds no media parts: ${entries.keys.filter { it.startsWith("word/") }}",
            entries.keys.any { it.startsWith("word/media/") },
        )
        assertTrue(
            "the front-page infographics the DCH template asks for are not in the package, so the " +
                "office's copy of this workshop carries figures the field copy does not: " +
                "${entries.keys.filter { it.startsWith("word/charts/") }}",
            entries.keys.any { it.startsWith("word/charts/chart") },
        )
    }

    @Test
    fun `an empty draft carries the cover and NOTHING under it`() {
        // The other half of the pair, and the sharper statement of the defect: it is not that the
        // empty file is short, it is that it has no sections in it whatever. A build that starts
        // printing headings over a workshop with nothing in it would be printing twenty-two
        // headings over blank half-pages, which is the failure `renderStageSection`'s omitIfEmpty
        // branch exists to prevent.
        val schema = shippedRegistry()
        val bytes = docxOf(documentOf(schema, emptyDraft()))
        val xml = documentXmlOf(bytes)

        assertEquals(
            "a workshop with no answers in it printed section headings",
            0,
            headingCount(xml),
        )
        assertEquals(
            "a workshop with no answers in it printed a table",
            0,
            bodyChildren(xml).count { it == "w:tbl" },
        )
        // And it is still a real, openable document rather than a truncated one — the cover is the
        // one thing that must survive an empty record, because a designer who exports too early
        // must get a file they can see is empty rather than a file Word refuses.
        assertTrue(
            "the package is not a .docx at all",
            entriesOf(bytes).keys.containsAll(
                listOf("[Content_Types].xml", "word/document.xml", "word/styles.xml"),
            ),
        )
    }
}
