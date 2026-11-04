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

class DocxContentTest {

    private val registryJson = Json { ignoreUnknownKeys = true }

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

    private fun filler(field: FieldDto): JsonElement? = when (field.type) {
        "RICH_TEXT" -> richDoc("Recorded during the ${field.label.lowercase()} session.")
        "IMAGE" -> JsonPrimitive("media-${field.key}")
        "IMAGE_LIST" -> buildJsonArray {
            add(JsonPrimitive("media-${field.key}-1"))
            add(JsonPrimitive("media-${field.key}-2"))
        }
        "FILE", "AUDIO", "VIDEO", "GEO" -> null
        "INT", "DECIMAL", "MONEY", "PERCENT" -> JsonPrimitive(7)
        "BOOL" -> JsonPrimitive(true)
        "DATE" -> JsonPrimitive("2026-02-03")
        "TIME" -> JsonPrimitive("10:30")
        "ENUM" -> field.options.firstOrNull()?.let { JsonPrimitive(it.value) }
        "MULTI_ENUM" -> field.options.firstOrNull()?.let {
            buildJsonArray { add(JsonPrimitive(it.value)) }
        }
        "TAGS" -> buildJsonArray { add(JsonPrimitive("recorded")) }
        "REF" -> JsonPrimitive("e0")
        else -> JsonPrimitive("${field.label} recorded")
    }

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

    private fun emptyDraft() = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = emptyMap(),
    )

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

    private fun documentOf(schema: SchemaResponse, draft: WorkshopDraft): ReportDocument =
        buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = "DCH_STANDARD",
            warnings = emptyList(),
            accent = "",
            imageFor = { id -> ImageRef(source = "/data/$id.png", widthPx = 1600, heightPx = 1200) },
            generatedAt = "2026-03-04T09:30:00Z",
        )

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
                tag.startsWith("/") -> if (depth == 0) break else depth -= 1
                tag.endsWith("/") -> if (depth == 0) out.add(tagName(tag))
                else -> {
                    if (depth == 0) out.add(tagName(tag))
                    depth += 1
                }
            }
        }
        return out.filter { it != "w:sectPr" }
    }

    private fun tagName(tag: String): String = tag.trimEnd('/').trim().substringBefore(' ')

    private fun headingCount(documentXml: String): Int =
        Regex("<w:pStyle w:val=\"Heading[1-4]\"/>").findAll(documentXml).count()

    private val coverFloorCap = 24

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

        assertTrue(
            "an empty draft produced ${empty.size} block-level elements, which is no longer a cover " +
                "sheet. Either the cover grew a great deal or a section is now printing on a " +
                "workshop that has nothing in it.",
            empty.size <= coverFloorCap,
        )
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
        assertTrue(
            "the package is not a .docx at all",
            entriesOf(bytes).keys.containsAll(
                listOf("[Content_Types].xml", "word/document.xml", "word/styles.xml"),
            ),
        )
    }
}
