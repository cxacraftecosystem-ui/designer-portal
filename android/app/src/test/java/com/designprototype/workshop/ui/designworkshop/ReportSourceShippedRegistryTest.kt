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

/**
 * THE DELIVERED DEFECT, MEASURED IN THE UNITS IT WAS REPORTED IN, against the registry the APK ships.
 *
 * [ReportSourceTest] asserts the merge on a four-stage registry it declares itself, and that is the
 * right shape for the merge rule. It is NOT enough to close this defect, because the defect was never
 * reported as "the merge is wrong": it was reported as a `.docx` pulled off a Galaxy M32 whose
 * `word/document.xml` held **ten paragraphs and 282 characters** over a workshop carrying 270 rows
 * across 22 stages. Nothing that stops one step short of the file can say whether that number moved.
 *
 * So this test drives the WHOLE path the screen drives — the shipped 22-stage
 * `design-workshop-schema.json`, the shipped DCH_STANDARD template, [reportSourceFor], then
 * [buildWorkshopDocument], and then [renderDocx], which needs only `java.util.zip` and therefore runs
 * here — and counts `<w:p>` elements and `<w:t>` characters in the OOXML part the bug report quotes.
 * If the merge ever stops reaching the file, these numbers collapse back to the reported ones and
 * this test says so in paragraphs rather than in prose.
 *
 * The second half is the regression that would be WORSE than the blank report: a report screen that
 * reads the server and then overwrites the courtyard's unsynced work with it. That is asserted over
 * every stage of the shipped registry at once rather than over one hand-picked stage.
 */
class ReportSourceShippedRegistryTest {

    // ── The registry the APK ships, not a hand-written stand-in ───────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Same walk-up as [DwParentGroupParityTest]: the module root differs between IDE and Gradle. */
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

    // ── The server's answer for the flagship workshop ────────────────────────────────────────────

    /**
     * A value of the shape the field's type implies, for the types a designer types into.
     *
     * MEDIA, GEO AND REF ARE LEFT EMPTY ON PURPOSE. A downloaded stage cannot carry this handset's
     * photographs (that is [ReportSource]'s documented contract) and a reference id resolved against
     * a device that has never held the referenced row prints as a raw id; neither belongs in a
     * measurement of how much TEXT reached the page. Everything a keyboard produces is filled, which
     * is what makes the paragraph count below a fair floor rather than a flattering one.
     */
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
                // Server bookkeeping travels inside the row object — see `_stages_payload`. `_clientKey`
                // is emitted only when the row has one, so an `_entryId`-only row is the ordinary case.
                .plus("_entryId" to JsonPrimitive("${entity.key}-entry-$index"))
                .plus("_ordinal" to JsonPrimitive(index))
        )
    }

    /** Every stage of the shipped registry, filled the way the seeded flagship workshop is. */
    private fun serverAnswer(rowsPerCollection: Int = 4): StageListDto = StageListDto(
        stages = schema.stages.associate { stage ->
            stage.key to StageBucketDto(
                singleton = stage.singleton?.let { singletonOf(it, stage.title) } ?: JsonObject(emptyMap()),
                collections = stage.collections.associate { it.key to rowsOf(it, rowsPerCollection) },
            )
        }
    )

    // ── The file itself ──────────────────────────────────────────────────────────────────────────

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

    /** `word/document.xml`, exactly the part the bug report was measured from. */
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

    /** `<w:p>` and `<w:p …>`, never `<w:pPr>`. */
    private fun paragraphs(xml: String): Int = Regex("<w:p(?=[ >])").findAll(xml).count()

    /** `<w:t>` and `<w:t xml:space="preserve">`, never `<w:tbl>`, `<w:tc>` or `<w:tr>`. */
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

    // ── 1. DOES THE DOCUMENT ACTUALLY GROW ───────────────────────────────────────────────────────

    @Test
    fun `the exported docx grows from the reported near-empty file to the whole workshop`() {
        // The handset as it was found: a draft for the workshop, three of its twenty-two stages ever
        // opened, nothing else downloaded.
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

        // THE OLD DATA PATH, reproduced exactly: WorkshopDraftStore.load and nothing else.
        val beforeXml = documentXml(document(onDevice))
        val before = paragraphs(beforeXml) to textChars(beforeXml)

        val afterXml = documentXml(document(sourceFor(onDevice).draft))
        val after = paragraphs(afterXml) to textChars(afterXml)

        val note = "device-only ${before.first} paragraphs / ${before.second} chars; " +
            "merged ${after.first} paragraphs / ${after.second} chars"
        // Printed so the measurement is in the test report, not only in a failure message. This is
        // the number the bug was reported as and the number that says the fix landed.
        println("word/document.xml — $note")

        // The delivered file was a valid OOXML document of ten paragraphs and 282 characters. The
        // device-only build here is of that order — a cover, a contents field, three answers.
        assertTrue("the device-only export should still be the near-empty file ($note)", before.first < 60)

        // And the fix has to move it by an order of magnitude, not by a stage or two. 22 stages of
        // singletons plus four rows of every collection in the registry is a document in the
        // hundreds of paragraphs; anything less means the merge is not reaching the writer.
        assertTrue("the merged export must be an order of magnitude larger ($note)", after.first > before.first * 10)
        assertTrue("and must carry the workshop's text, not just its headings ($note)", after.second > 20_000)
    }

    @Test
    fun `a device that has never opened this workshop still exports the whole of it`() {
        // The supervisor's handset: no draft file at all. Before the fix this produced a cover page
        // over nothing; there was no local read that could have produced anything else.
        val fromNothing = sourceFor(local = null)
        assertNotNull("a workshop held only on the server must still have a draft to build from", fromNothing.draft)

        val xml = documentXml(document(fromNothing.draft))
        assertTrue("the whole workshop must reach the file (${textChars(xml)} chars)", textChars(xml) > 20_000)

        // AND THE COVER IS TITLED, which on a device with no draft it could not have been before:
        // `reportMetaFor` reads the report title out of the stage-20 answer, and stage 20 is now in
        // the merged draft like every other stage.
        val cover = document(fromNothing.draft).blocks.filterIsInstance<CoverBlock>().first()
        assertTrue("the cover must be titled, from the server's own stage 20", cover.title.isNotBlank())

        // With nothing to name it on either side, the export's long-standing fallback still stands —
        // a titled document rather than a blank cover.
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

    // ── 2. IS UNSYNCED LOCAL WORK SAFE ───────────────────────────────────────────────────────────

    @Test
    fun `not one stage this device holds work for is altered by the merge`() {
        // The dangerous fix is the one that reads the server and writes it over the courtyard. This
        // asserts the negative over the WHOLE registry rather than over one chosen stage: every stage
        // is dirty locally, every stage is fuller on the server, and every local stage must come back
        // identical — same object, same rows, same bookkeeping.
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

        // The draft's own belongings survive too — the photographs are the ones a report annexure
        // resolves against, and losing them here would empty the photographic record.
        assertEquals(onDevice.media, merged.media)
        assertEquals(onDevice.title, merged.title)
        assertEquals(onDevice.remoteId, merged.remoteId)
        assertEquals(onDevice.sync, merged.sync)
    }

    @Test
    fun `a stage the server has never seen survives beside the stages it fills in`() {
        // The whole point of a merge rather than a swap: one stage captured with no signal, twenty-one
        // downloaded around it, and the unsynced one still in the file.
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
        /*
          THE ASSERTION THAT REPLACES THE `__unsynced` FIXTURE, made head-on instead of by accident.

          This file used to stand for "one stage the device holds work for" with a stage whose only
          value was a double-underscore marker, and that fixture went green for the wrong reason: the
          merge counted ANY key as work. It is the wrong rule. `WorkshopSync.wireData` strips every
          `_`-prefixed key out of a PUT and `renderEntity` walks `liveFields`, so an underscore key
          can neither travel nor print — and a stage the document cannot print a syllable of was
          being allowed to keep the server's whole copy of that stage out of the file. That is the
          reported defect (a .docx of ten paragraphs over a 270-row workshop) arriving one stage at a
          time, which is worse, because the file then looks complete.

          The realistic shape is not a marker: `DwRecordingPlaceCard` is offered on all 22 stages, so
          a designer who opens stage 3 with no signal, answers only "where are you?" and leaves has
          made exactly this stage. It must cost the report nothing — and it must not cost the
          designer their answer either.
        */
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

        // 1. The server's copy is adopted, and the built-from line says so rather than claiming the
        //    stage for a device that holds nothing printable of it.
        assertEquals(
            "the server's participants must reach the merged stage",
            4,
            stage.rows.count { it.id.substringBefore('#') == "participant" },
        )
        assertTrue(source.filledFromServer.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))
        assertFalse(source.keptFromDevice.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))

        // 2. …and the designer's own answer is carried across the fill-in rather than deleted by it.
        //    "Not work" is a merge decision; erasing the answer would be a different act. See
        //    [DwLocationField], which documents this key as surviving in the draft the report is
        //    generated from.
        assertEquals(
            "the recording place must survive the stage being filled in",
            JsonPrimitive("Barpali, Bargarh"),
            stage.values[DW_RECORDING_PLACE_KEY],
        )

        // 3. Measured where the defect was reported — in the file. A stage held only as provenance
        //    must suppress not one paragraph of the document a device with no draft at all produces.
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
        // DELETING IS FIELDWORK TOO. Eight of the twenty-two stages hold nothing but collections, so
        // deleting the last cost sheet leaves stage 17 with no values and no rows — the exact shape
        // the merge reads as "this device holds nothing here". The deletion is sitting in
        // `emptiedEntities` waiting for signal; reading the server's copy back over it would print,
        // in the document handed to the officer, the rows the designer deleted in front of them.
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
        // …and ONLY that collection. The other three lists in the same stage were never deleted and
        // are the whole reason the fill-in exists.
        assertEquals(4, merged.rows.count { it.id.substringBefore('#') == "costMaterialLine" })
        assertEquals(4, merged.rows.count { it.id.substringBefore('#') == "buyerLink" })
        assertEquals("the record of the deletion travels on", listOf("costSheet"), merged.emptiedEntities)
    }

    @Test
    fun `a stage emptied entirely on this device stays empty and is counted as neither side's`() {
        // Stage 11 has one collection and no singleton, so emptying it empties the stage. Nothing
        // must come back, and the built-from line must not claim a stage the file does not contain.
        //
        // THE RECORDING PLACE IS IN THIS FIXTURE ON PURPOSE, and it is the same key the test above
        // turns on. `stageDraftFromRemote` carries this device's `_`-prefixed answers across the
        // fill-in, so the stage that comes back out of it is no longer value-less — and the
        // "is there anything here?" test that follows it therefore has to be [holdsWork] and not a
        // hand-written `values.isEmpty()`, or this emptied stage is reported to the designer as
        // "downloaded from the server just now" while the document contains nothing of it.
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
        // A media id in a server answer names a MediaFile on the server; `imageFor` resolves ids
        // against this handset's own draft.media. Writing server ids into StageDraft.mediaIds would
        // point the renderer at an id space it cannot read.
        val merged = sourceFor(local = null).draft!!
        assertTrue(
            "a downloaded stage prints its words and skips its pictures",
            merged.stages.values.all { it.mediaIds.isEmpty() },
        )
        assertTrue("and no media is invented on the draft", merged.media.isEmpty())
    }

    // ── 3. IS THE OFFLINE PATH HONEST ────────────────────────────────────────────────────────────

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

        // The export must still happen, with everything this device holds in it.
        assertEquals(onDevice.stages, source.draft!!.stages)
        assertTrue(documentXml(document(source.draft)).contains("courtyard sketch 2"))

        // …and it must be announced. NOT as a diagnosis: this code knows the read returned nothing and
        // does not know why. `StageScreen` says "there is no connection, or the request failed" on the
        // same evidence, and a report handed to a ministry officer is the last place to assert a cause
        // that was never observed — a designer told "there is no connection" while five bars are
        // showing stops believing the notice, and the next one matters.
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
        assertTrue(notOnServer!!.contains("not been created on the server"))
    }

    @Test
    fun `a successful read is silent and the built-from line still names both halves`() {
        val onDevice = WorkshopDraft(
            workshopId = WORKSHOP_ID,
            remoteId = WORKSHOP_ID,
            stages = mapOf(
                // A KEY OF THE SHIPPED REGISTRY, NOT `__unsynced` AND NOT AN INVENTED ONE. This
                // fixture used a double-underscore marker to stand for "one stage the device holds
                // work for", which stopped being true when `reportSourceFor` learned that an
                // `_`-prefixed key is not work: `wireData` strips every one of them from the payload
                // and `renderEntity` walks `liveFields`, so a stage whose only content is an
                // underscore key can neither travel nor print, and treating it as the device's kept
                // the server's whole copy of that stage out of the report. The marker was an
                // accidental instance of exactly that defect — asserted head-on, against this same
                // 22-stage registry, in `an underscore-only stage is filled in from the server` below.
                //
                // `craftName` is a real STAGE 1 field with `reportRole: COVER_FIELD`, so the "1
                // stage(s) saved on this device" this test counts is a stage that genuinely reaches
                // the page. A stand-in key that no entity declares would satisfy the predicate and
                // still print nothing, which is how a built-from line starts counting stages the
                // document does not contain.
                "WORKSHOP_SETUP" to StageDraft(
                    stageId = "WORKSHOP_SETUP",
                    values = mapOf("craftName" to JsonPrimitive("typed in the courtyard")),
                )
            ),
        )
        val source = sourceFor(onDevice)
        assertTrue("nothing to warn about once the server answered", source.deviceOnlyNote == null)
        assertTrue(source.builtFromLine.contains("${schema.stages.size - 1} stage(s) downloaded"))
        assertTrue(source.builtFromLine.contains("1 stage(s) saved on this device"))
    }

    // ── 4. THE FILE ITSELF ADMITS WHAT IT MAY BE MISSING ─────────────────────────────────────────

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
        // THE SCREEN'S NOTICE IS GONE THE MOMENT THE DESIGNER LEAVES THE SCREEN. The officer who
        // opens this .docx next month was never on it, and a short report is internally consistent —
        // right cover, right contents, fewer sections — so nothing in the file would otherwise say
        // that eighteen stages were never downloaded. `fieldCopyNote` is the slot this app already
        // reserves for exactly this class of fact.
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
            footers.any { it.contains("could not be read from the server") },
        )
    }

    @Test
    fun `a report built from the server's own copy claims nothing about being short`() {
        val source = sourceFor(local = null)
        assertFalse(source.serverCopyUnread)
        assertTrue(
            "a whole file must not carry a caveat — a provenance line nobody believes is worse than none",
            coverOf(source).footerLines.none { it.contains("could not be read from the server") },
        )
    }

    @Test
    fun `a workshop that exists nowhere but this device is complete and says so by saying nothing`() {
        // No server record at all: there is nothing anywhere else this file could be missing, so the
        // cover must stay silent even though the screen still tells the designer to send it up.
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
        assertTrue(coverOf(source).footerLines.none { it.contains("could not be read from the server") })
    }

    // ── 5. TWO EXPORTS OF UNCHANGED DATA ARE THE SAME FILE ───────────────────────────────────────

    @Test
    fun `re-exporting unchanged data produces byte-identical OOXML`() {
        // `recordDesignWorkshopExport` sends a SHA-256 and the web's report history answers "the
        // revised copy you sent was the same file as last time" from it alone.
        val first = documentXml(document(sourceFor(local = null).draft))
        val second = documentXml(document(sourceFor(local = null).draft))
        assertEquals(first, second)
    }

    private companion object {
        const val WORKSHOP_ID = "22222222-2222-4222-8222-222222222222"
    }
}
