package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwTranscriptCache
import com.designprototype.workshop.data.DwTranscriptItem
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageListDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.dwTranscriptCacheOf
import com.designprototype.workshop.data.dwTranscriptSelection
import com.designprototype.workshop.data.dwTranscriptsPendingLine
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.STAGE_20_SETTINGS
import com.designprototype.workshop.report.SettingReach
import com.designprototype.workshop.report.TRANSCRIPT_DEFAULT_HEADING
import com.designprototype.workshop.report.reportTemplate
import com.designprototype.workshop.report.reportWarnings
import com.designprototype.workshop.report.runsText
import com.designprototype.workshop.report.transcriptRefusedWarning
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DA-29: the transcript annexure on the phone — which recordings it prints, what the designer is
 * told about the rest, and that the document the export screen writes actually carries it.
 */
class ReportTranscriptAnnexureTest {

    private val schema = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 5, key = "TRADITIONAL_PROCESS_BASELINE", title = "Traditional process",
                entities = listOf(
                    EntityDto(
                        key = "traditionalProcess", cardinality = "SINGLETON", title = "Process",
                        fields = listOf(
                            FieldDto(key = "notes", label = "Notes", type = "TEXT", tier = "BASIC"),
                            FieldDto(key = "artisanAudio", label = "Artisan audio", type = "AUDIO", tier = "BASIC"),
                        ),
                    ),
                ),
            ),
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype Development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", cardinality = "COLLECTION", title = "Prototype",
                        fields = listOf(
                            FieldDto(key = "name", label = "Name", type = "TEXT", tier = "BASIC"),
                            FieldDto(key = "voiceNote", label = "Voice note", type = "AUDIO", tier = "BASIC"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun item(id: String, stage: Int, key: String, label: String, text: String, status: String = "COMPLETED") =
        DwTranscriptItem(
            mediaId = id, stageKey = key, stageNumber = stage, stageTitle = "Stage $stage",
            fieldLabel = label, durationSeconds = 60, status = status, text = text,
        )

    private fun draft(settings: Map<String, JsonElement>, vararg extraVoiceNotes: String) = WorkshopDraft(
        workshopId = "local-1",
        title = "Barpali",
        stages = mapOf(
            "TRADITIONAL_PROCESS_BASELINE" to StageDraft(
                stageId = "TRADITIONAL_PROCESS_BASELINE",
                values = mapOf("notes" to JsonPrimitive("Warp first"), "artisanAudio" to JsonPrimitive("a1")),
            ),
            "PROTOTYPE_DEVELOPMENT" to StageDraft(
                stageId = "PROTOTYPE_DEVELOPMENT",
                rows = (listOf("a2") + extraVoiceNotes).mapIndexed { i, id ->
                    DraftRow(
                        id = "prototype#${i + 1}",
                        values = mapOf("name" to JsonPrimitive("Runner ${i + 1}"), "voiceNote" to JsonPrimitive(id)),
                    )
                },
            ),
            "REPORT_GENERATION" to StageDraft(stageId = "REPORT_GENERATION", values = settings),
        ),
    )

    private val asked = mapOf("includeTranscripts" to JsonPrimitive(true))

    private val cache = DwTranscriptCache(
        workshopId = "local-1",
        items = listOf(
            item("a2", 13, "PROTOTYPE_DEVELOPMENT", "Voice note", "**Speaker 1:** Too dark."),
            item("a1", 5, "TRADITIONAL_PROCESS_BASELINE", "Artisan audio", "We tie the warp first."),
            // Held on the server and not referenced by this draft: never printed.
            item("zz", 5, "TRADITIONAL_PROCESS_BASELINE", "Elsewhere", "Not in this report."),
        ),
        referencedIds = listOf("a1", "a2", "zz", "refused-1"),
        fetchedAt = "2026-10-17T00:00:00Z",
    )

    private fun build(draft: WorkshopDraft, transcripts: DwTranscriptCache?): ReportDocument = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = "local-1",
        templateId = "DCH_STANDARD",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        generatedAt = "2026-10-17T09:30:00Z",
        transcripts = transcripts,
    )

    private fun headings(doc: ReportDocument) = doc.blocks.filterIsInstance<HeadingBlock>().map { runsText(it.runs) }

    @Test
    fun `the phone's report carries the transcripts it holds, in the server's order`() {
        val doc = build(draft(asked), cache)
        val all = headings(doc)
        val at = all.indexOf(TRANSCRIPT_DEFAULT_HEADING)
        assertTrue("no transcript annexure in the phone's report: $all", at >= 0)
        // `load_transcript_items` sorts by stage number first — stage 5 before stage 13.
        assertEquals(listOf("Artisan audio", "Voice note"), all.subList(at + 1, at + 3))
        val text = doc.blocks.filterIsInstance<ParagraphBlock>().joinToString("\n") { runsText(it.runs) }
        assertTrue(text.contains("We tie the warp first."))
        assertFalse("a recording this draft does not reference was printed", text.contains("Not in this report."))
    }

    @Test
    fun `no annexure unless stage 20 asked for it`() {
        assertFalse(headings(build(draft(emptyMap()), cache)).contains(TRANSCRIPT_DEFAULT_HEADING))
        // And none when the phone holds nothing, rather than a heading over nothing.
        assertFalse(headings(build(draft(asked), null)).contains(TRANSCRIPT_DEFAULT_HEADING))
    }

    @Test
    fun `recordings the phone has not been sent are counted, not silently dropped`() {
        val selection = dwTranscriptSelection(schema, draft(asked, "new-on-phone", "refused-1", "newer"), cache)
        assertEquals(listOf("a1", "a2"), selection.items.map { it.mediaId })
        assertEquals(2, selection.notOnPhone)
        assertEquals(1, selection.refused)
        assertEquals("2 transcripts will be added when you're online.", dwTranscriptsPendingLine(selection.notOnPhone))
        assertEquals("1 transcript will be added when you're online.", dwTranscriptsPendingLine(1))
        assertNull(dwTranscriptsPendingLine(0))
        assertEquals(
            "1 recording(s) attached to this workshop could not be included in the transcript annexure: " +
                "they were uploaded by another account, or the file is gone.",
            transcriptRefusedWarning(selection.refused),
        )
        // Never read on this phone at all: every recording is outstanding.
        assertEquals(2, dwTranscriptSelection(schema, draft(asked), null).notOnPhone)
    }

    @Test
    fun `the stage read's transcripts are decoded and kept with what the server referenced`() {
        val wire = """{"stages":{"PROTOTYPE_DEVELOPMENT":{"singleton":{},"collections":{"prototype":[
            {"name":"Runner 1","voiceNote":"a2","_entryId":"e1","_ordinal":0},
            {"name":"Runner 2","voiceNote":"gone","_entryId":"e2","_ordinal":1}]},"custom":{}}},
            "completeness":{},"transcripts":{"a2":{"mediaId":"a2","stageKey":"PROTOTYPE_DEVELOPMENT",
            "stageNumber":13,"stageTitle":"Prototype Development","entityKey":"prototype",
            "fieldKey":"voiceNote","fieldLabel":"Voice note","label":"Voice note","filename":"n.m4a",
            "recordedAt":"","durationSeconds":null,"durationText":"","speakerCount":1,"wordCount":2,
            "firstLine":"Too dark.","status":"COMPLETED","hasTranscript":true,"text":"Too dark."}},
            "schemaVersion":"x"}"""
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString(StageListDto.serializer(), wire)
        val kept = dwTranscriptCacheOf("local-1", schema, dto.stages, dto.transcripts)!!
        assertEquals(listOf("a2"), kept.items.map { it.mediaId })
        assertEquals("Too dark.", kept.items.single().text)
        assertEquals(listOf("a2", "gone"), kept.referencedIds)
        // An older server with no `transcripts` key must not replace a good copy with nothing.
        assertNull(dwTranscriptCacheOf("local-1", schema, dto.stages, null))
        assertNull(dwTranscriptCacheOf("local-1", schema, emptyMap<String, StageBucketDto>(), null))
    }

    @Test
    fun `the ledger says the toggle is applied and the export no longer disowns the annexure`() {
        assertEquals(
            SettingReach.APPLIED,
            STAGE_20_SETTINGS.single { it.key == "includeTranscripts" }.reach,
        )
        val said = reportWarnings("DCH_STANDARD", reportTemplate("DCH_STANDARD"), asked, "DOCX")
        assertTrue("the annexure is built here now: $said", said.none { it.contains("ranscript") })
    }
}
