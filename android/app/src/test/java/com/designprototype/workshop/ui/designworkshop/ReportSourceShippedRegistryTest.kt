package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftMedia
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageListDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.singleton
import com.designprototype.workshop.report.CoverBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.renderDocx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class ReportSourceShippedRegistryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

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

    private val schema: SchemaResponse = json.decodeFromString(
        SchemaResponse.serializer(),
        repoFile(
            "src/main/assets/design-workshop-schema.json",
            "app/src/main/assets/design-workshop-schema.json",
            "android/app/src/main/assets/design-workshop-schema.json",
        ).readText()
    )

    private fun valueFor(field: FieldDto, seed: String): JsonElement? = when (DwFieldType.of(field.type)) {
        DwFieldType.TEXT, DwFieldType.LONG_TEXT, DwFieldType.RICH_TEXT, DwFieldType.PHONE,
        DwFieldType.EMAIL, DwFieldType.URL, DwFieldType.TIME ->
            JsonPrimitive("${field.label} — $seed")
        DwFieldType.INT -> JsonPrimitive(7)
        DwFieldType.DECIMAL, DwFieldType.MONEY -> JsonPrimitive("1650.00")
        DwFieldType.PERCENT -> JsonPrimitive(42)
        DwFieldType.DATE -> JsonPrimitive("2026-02-10")
        DwFieldType.BOOL -> JsonPrimitive(true)
        DwFieldType.ENUM -> field.options.firstOrNull()?.let { JsonPrimitive(it.value) }
        else -> null
    }

    private fun singletonOf(entity: EntityDto, seed: String): JsonObject = JsonObject(
        entity.liveFields.mapNotNull { field -> valueFor(field, seed)?.let { field.key to it } }.toMap()
    )

    private fun rowsOf(entity: EntityDto, count: Int): List<JsonObject> = (1..count).map { index ->
        JsonObject(
            entity.liveFields
                .mapNotNull { field -> valueFor(field, "row $index")?.let { field.key to it } }
                .toMap()
                .plus("_entryId" to JsonPrimitive("${entity.key}-entry-$index"))
                .plus("_ordinal" to JsonPrimitive(index))
        )
    }

    private fun serverAnswer(rowsPerCollection: Int = 4): StageListDto = StageListDto(
        stages = schema.stages.associate { stage ->
            stage.key to StageBucketDto(
                singleton = stage.singleton?.let { singletonOf(it, stage.title) } ?: JsonObject(emptyMap()),
                collections = stage.collections.associate { it.key to rowsOf(it, rowsPerCollection) },
            )
        }
    )

    private fun document(draft: WorkshopDraft?): ReportDocument = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = WORKSHOP_ID,
        templateId = "DCH_STANDARD",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        generatedAt = "2026-02-10T09:00:00Z",
    )

    private fun documentXml(document: ReportDocument): String {
        val out = ByteArrayOutputStream()
        renderDocx(document, { null }, out)
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw AssertionError("the .docx carries no word/document.xml")
    }

    private fun paragraphs(xml: String): Int = Regex("<w:p(?=[ >])").findAll(xml).count()

    private fun textChars(xml: String): Int = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        .findAll(xml)
        .sumOf { it.groupValues[1].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").length }

    private fun sourceFor(
        local: WorkshopDraft?,
        remoteId: String? = WORKSHOP_ID,
        answer: StageListDto? = serverAnswer(),
    ) = reportSourceFor(
        schema = schema,
        workshopId = WORKSHOP_ID,
        local = local,
        remoteId = remoteId,
        remote = answer,
    )

    @Test
    fun `the exported docx grows from the reported near-empty file to the whole workshop`() {
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            title = "Sambalpuri ikat — Barpali",
            remoteId = WORKSHOP_ID,
            stages = mapOf(
                "WORKSHOP_SETUP" to StageDraft(
                    stageId = "WORKSHOP_SETUP",
                    values = mapOf(
                        "craftName" to JsonPrimitive("Sambalpuri ikat"),
                        "clusterName" to JsonPrimitive("Barpali"),
                        "state" to JsonPrimitive("Odisha"),
                    ),
                ),
            ),
        )

        val beforeXml = documentXml(document(onDevice))
        val before = paragraphs(beforeXml) to textChars(beforeXml)

        val afterXml = documentXml(document(sourceFor(onDevice).draft))
        val after = paragraphs(afterXml) to textChars(afterXml)

        val note = "device-only ${before.first} paragraphs / ${before.second} chars; " +
            "merged ${after.first} paragraphs / ${after.second} chars"
        println("word/document.xml — $note")

        assertTrue("the device-only export should still be the near-empty file ($note)", before.first < 60)

        assertTrue("the merged export must be an order of magnitude larger ($note)", after.first > before.first * 10)
        assertTrue("and must carry the workshop's text, not just its headings ($note)", after.second > 20_000)
    }

    @Test
    fun `a device that has never opened this workshop still exports the whole of it`() {
        val fromNothing = sourceFor(local = null)
        assertNotNull("a workshop held only on the server must still have a draft to build from", fromNothing.draft)

        val xml = documentXml(document(fromNothing.draft))
        assertTrue("the whole workshop must reach the file (${textChars(xml)} chars)", textChars(xml) > 20_000)

        val cover = document(fromNothing.draft).blocks.filterIsInstance<CoverBlock>().first()
        assertTrue("the cover must be titled, from the server's own stage 20", cover.title.isNotBlank())

        val unnamed = sourceFor(
            local = null,
            answer = StageListDto(stages = serverAnswer().stages - "REPORT_GENERATION"),
        )
        assertEquals(
            "Design & Prototype Workshop",
            document(unnamed.draft).blocks.filterIsInstance<CoverBlock>().first().title,
        )
    }

    @Test
    fun `every stage of the shipped registry reaches the merged draft`() {
        val source = sourceFor(local = null)
        val merged = source.draft!!

        assertEquals(
            "all 22 stages of the registry the phone is holding",
            schema.stages.map { it.key }.toSet(),
            merged.stages.keys,
        )
        schema.stages.forEach { stage ->
            val stored = merged.stages.getValue(stage.key)
            stage.collections.forEach { entity ->
                assertEquals(
                    "${stage.key}/${entity.key} rows",
                    4,
                    stored.rows.count { it.id.substringBefore('#') == entity.key },
                )
            }
        }
    }

    @Test
    fun `not one stage this device holds work for is altered by the merge`() {
        val dirty = schema.stages.associate { stage ->
            stage.key to StageDraft(
                stageId = stage.key,
                title = stage.title,
                order = stage.number,
                values = mapOf("__unsynced" to JsonPrimitive("typed at the loom, never sent")),
                rows = listOf(
                    DraftRow(
                        id = dwRowId(stage.collections.firstOrNull()?.key ?: "row", "local-1"),
                        values = mapOf("name" to JsonPrimitive("courtyard row")),
                    )
                ),
                mediaIds = listOf("media-${stage.number}"),
                notes = "written with no signal",
                completedAt = "2026-02-09T18:00:00Z",
                emptiedEntities = listOf("costSheet"),
                stageSeen = false,
            )
        }
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            title = "Barpali cluster",
            remoteId = WORKSHOP_ID,
            stages = dirty,
            media = listOf(DraftMedia(id = "media-1", relativePath = "media/a.jpg")),
        )

        val source = sourceFor(onDevice)
        val merged = source.draft!!

        assertEquals("every stage must come back exactly as the device held it", dirty, merged.stages)
        assertTrue("and none of them may be reported as filled from the server", source.filledFromServer.isEmpty())
        assertEquals(schema.stages.size, source.keptFromDevice.size)

        assertEquals(onDevice.media, merged.media)
        assertEquals(onDevice.title, merged.title)
        assertEquals(onDevice.remoteId, merged.remoteId)
        assertEquals(onDevice.sync, merged.sync)
    }

    @Test
    fun `a stage the server has never seen survives beside the stages it fills in`() {
        val unsynced = StageDraft(
            stageId = "SKETCH_DEVELOPMENT",
            values = mapOf("__unsynced" to JsonPrimitive("drawn at the loom")),
            rows = (1..3).map {
                DraftRow(dwRowId("sketch", "local-$it"), mapOf("name" to JsonPrimitive("courtyard sketch $it")))
            },
        )
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            remoteId = WORKSHOP_ID,
            stages = mapOf("SKETCH_DEVELOPMENT" to unsynced),
        )

        val merged = sourceFor(onDevice).draft!!
        assertEquals(unsynced, merged.stages.getValue("SKETCH_DEVELOPMENT"))
        assertEquals(schema.stages.size, merged.stages.size)

        val xml = documentXml(document(merged))
        assertTrue("the unsynced sketches must be in the printed file", xml.contains("courtyard sketch 3"))
    }

    @Test
    fun `an underscore-only stage is filled in from the server and keeps its provenance`() {
        val provenanceOnly = StageDraft(
            stageId = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
            values = mapOf(DW_RECORDING_PLACE_KEY to JsonPrimitive("Barpali, Bargarh")),
        )
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            remoteId = WORKSHOP_ID,
            stages = mapOf("WORKSHOP_PLAN_PARTICIPANTS_OPENING" to provenanceOnly),
        )
        val source = sourceFor(onDevice)
        val stage = source.draft!!.stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING")

        assertEquals(
            "the server's participants must reach the merged stage",
            4,
            stage.rows.count { it.id.substringBefore('#') == "participant" },
        )
        assertTrue(source.filledFromServer.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))
        assertFalse(source.keptFromDevice.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))

        assertEquals(
            "the recording place must survive the stage being filled in",
            JsonPrimitive("Barpali, Bargarh"),
            stage.values[DW_RECORDING_PLACE_KEY],
        )

        val whole = paragraphs(documentXml(document(sourceFor(local = null).draft)))
        val withProvenance = paragraphs(documentXml(document(source.draft)))
        assertEquals(
            "an underscore-only stage must cost the file nothing " +
                "($withProvenance paragraphs against $whole)",
            whole,
            withProvenance,
        )
    }

    @Test
    fun `a collection emptied on this device is not read back in from the server`() {
        val emptiedHere = StageDraft(
            stageId = "COSTING_MARKET_LINKAGE",
            emptiedEntities = listOf("costSheet"),
        )
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            remoteId = WORKSHOP_ID,
            stages = mapOf("COSTING_MARKET_LINKAGE" to emptiedHere),
        )

        val merged = sourceFor(onDevice).draft!!.stages.getValue("COSTING_MARKET_LINKAGE")
        assertTrue(
            "the deleted cost sheets must not come back",
            merged.rows.none { it.id.substringBefore('#') == "costSheet" },
        )
        assertEquals(4, merged.rows.count { it.id.substringBefore('#') == "costMaterialLine" })
        assertEquals(4, merged.rows.count { it.id.substringBefore('#') == "buyerLink" })
        assertEquals("the record of the deletion travels on", listOf("costSheet"), merged.emptiedEntities)
    }

    @Test
    fun `a stage emptied entirely on this device stays empty and is counted as neither side's`() {
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            remoteId = WORKSHOP_ID,
            stages = mapOf(
                "SKETCH_DEVELOPMENT" to StageDraft(
                    stageId = "SKETCH_DEVELOPMENT",
                    values = mapOf(DW_RECORDING_PLACE_KEY to JsonPrimitive("Barpali, Bargarh")),
                    emptiedEntities = listOf("sketch"),
                )
            ),
        )
        val source = sourceFor(onDevice)

        assertTrue(source.draft!!.stages.getValue("SKETCH_DEVELOPMENT").rows.isEmpty())
        assertFalse("an empty stage is not 'downloaded from the server just now'",
            source.filledFromServer.contains("SKETCH_DEVELOPMENT"))
        assertFalse(source.keptFromDevice.contains("SKETCH_DEVELOPMENT"))
        assertEquals(schema.stages.size - 1, source.filledFromServer.size)
    }

    @Test
    fun `the merge never fabricates a photograph this device cannot open`() {
        val merged = sourceFor(local = null).draft!!
        assertTrue(
            "a downloaded stage prints its words and skips its pictures",
            merged.stages.values.all { it.mediaIds.isEmpty() },
        )
        assertTrue("and no media is invented on the draft", merged.media.isEmpty())
    }

    @Test
    fun `a failed read still exports what the device holds and says the read failed`() {
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            title = "Barpali cluster",
            remoteId = WORKSHOP_ID,
            stages = mapOf(
                "SKETCH_DEVELOPMENT" to StageDraft(
                    stageId = "SKETCH_DEVELOPMENT",
                    rows = (1..3).map {
                        DraftRow(dwRowId("sketch", "local-$it"), mapOf("name" to JsonPrimitive("courtyard sketch $it")))
                    },
                )
            ),
        )
        val source = sourceFor(onDevice, remoteId = WORKSHOP_ID, answer = null)

        assertEquals(onDevice.stages, source.draft!!.stages)
        assertTrue(documentXml(document(source.draft)).contains("courtyard sketch 2"))

        val note = source.deviceOnlyNote
        assertNotNull("a partial export must be announced before the file is handed over", note)
        assertTrue("the note must not diagnose a cause it did not observe: $note", note!!.contains("could not be read"))
        assertTrue("and must still offer the likely cause: $note", note.contains("no connection"))
        assertTrue("and must say what the file therefore holds: $note", note.contains("this device"))
    }

    @Test
    fun `a workshop with no server record is told apart from a read that failed`() {
        val onDevice = WorkshopDraft(workshopId = "local-abc", stages = emptyMap())
        val notOnServer = sourceFor(onDevice, remoteId = null, answer = null).deviceOnlyNote
        val readFailed = sourceFor(onDevice, remoteId = WORKSHOP_ID, answer = null).deviceOnlyNote

        assertNotNull(notOnServer)
        assertNotNull(readFailed)
        assertFalse("the two states must not share a sentence", notOnServer == readFailed)
        assertTrue(notOnServer!!.contains("has not been uploaded yet"))
    }

    @Test
    fun `a successful read is silent and the built-from line still names both halves`() {
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            remoteId = WORKSHOP_ID,
            stages = mapOf(
                "WORKSHOP_SETUP" to StageDraft(
                    stageId = "WORKSHOP_SETUP",
                    values = mapOf("craftName" to JsonPrimitive("typed in the courtyard")),
                )
            ),
        )
        val source = sourceFor(onDevice)
        assertTrue("nothing to warn about once the server answered", source.deviceOnlyNote == null)
        assertTrue(source.builtFromLine.contains("${schema.stages.size - 1} stages downloaded"))
        assertTrue(source.builtFromLine.contains("1 stage saved on this device"))
    }

    private fun coverOf(source: ReportSource): CoverBlock {
        val plan = reportPlanFor(
            schema = schema,
            draft = source.draft,
            workshopId = WORKSHOP_ID,
            requestedTemplateId = "DCH_STANDARD",
            requestedAccent = "",
            format = "DOCX",
            generatedAt = "2026-02-10T09:00:00Z",
            serverCopyUnread = source.serverCopyUnread,
        )
        return buildWorkshopDocument(
            schema = schema,
            draft = source.draft,
            workshopId = WORKSHOP_ID,
            templateId = "DCH_STANDARD",
            warnings = emptyList(),
            accent = "",
            imageFor = { null },
            generatedAt = "2026-02-10T09:00:00Z",
            plan = plan,
        ).blocks.filterIsInstance<CoverBlock>().first()
    }

    @Test
    fun `a report built without the server's copy admits it on its own cover`() {
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            title = "Barpali cluster",
            remoteId = WORKSHOP_ID,
            stages = mapOf(
                "WORKSHOP_SETUP" to StageDraft(
                    stageId = "WORKSHOP_SETUP",
                    values = mapOf("craftName" to JsonPrimitive("Sambalpuri ikat")),
                )
            ),
        )
        val source = sourceFor(onDevice, remoteId = WORKSHOP_ID, answer = null)
        assertTrue("the workshop exists on the server and the read failed", source.serverCopyUnread)

        val footers = coverOf(source).footerLines
        assertTrue(
            "the file must say what the screen said: $footers",
            footers.any { it.contains("prepared without a connection") },
        )
    }

    @Test
    fun `a report built from the server's own copy claims nothing about being short`() {
        val source = sourceFor(local = null)
        assertFalse(source.serverCopyUnread)
        assertTrue(
            "a whole file must not carry a caveat — a provenance line nobody believes is worse than none",
            coverOf(source).footerLines.none { it.contains("prepared without a connection") },
        )
    }

    @Test
    fun `a workshop that exists nowhere but this device is complete and says so by saying nothing`() {
        val onDevice = WorkshopDraft(
            workshopId = "local-abc",
            stages = mapOf(
                "WORKSHOP_SETUP" to StageDraft(
                    stageId = "WORKSHOP_SETUP",
                    values = mapOf("craftName" to JsonPrimitive("Sambalpuri ikat")),
                )
            ),
        )
        val source = sourceFor(onDevice, remoteId = null, answer = null)
        assertFalse("nothing on the server means nothing missing", source.serverCopyUnread)
        assertNotNull("the designer is still told to send it up", source.deviceOnlyNote)
        assertTrue(coverOf(source).footerLines.none { it.contains("prepared without a connection") })
    }

    @Test
    fun `re-exporting unchanged data produces byte-identical OOXML`() {
        val first = documentXml(document(sourceFor(local = null).draft))
        val second = documentXml(document(sourceFor(local = null).draft))
        assertEquals(first, second)
    }

    private companion object {
        const val WORKSHOP_ID = "22222222-2222-4222-8222-222222222222"
    }
}
