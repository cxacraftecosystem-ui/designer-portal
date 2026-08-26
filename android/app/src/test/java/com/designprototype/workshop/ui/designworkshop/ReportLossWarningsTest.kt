package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwTier
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.MetricRowBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.Presentation
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.ReportMeta
import com.designprototype.workshop.report.ReportTemplate
import com.designprototype.workshop.report.ReportTheme
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TemplateSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TWO WARNINGS THE HANDSET GREW ON 2026-08-26, AGAINST THE SENTENCES `build_report` EMITS.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS FILE EXISTS AT ALL, GIVEN THAT `ReportDocumentTest` ALREADY BUILDS DOCUMENTS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Every other assertion in this directory is about what IS in the document. These two warnings are
 * about what is NOT — and an omission is invisible from the outside, which is precisely why the
 * repository has now paid for the same shape of defect five times over: `mapNotNull` residue dropped
 * where it was produced, a tier cap that hid seventeen filled fields, a cover table that silently
 * lost its overflow rows, ninety-eight RICH_TEXT fields, and a photograph cap that thinned a gallery
 * with nothing anywhere saying so. A test that only ever asks "is the prose there" passes through all
 * five.
 *
 * So the assertions here are of three kinds and all three matter:
 *
 *  1. **The arithmetic.** How many were dropped, and against which stage.
 *  2. **The sentence, character for character.** [dwPhotographsOverCapNote] and
 *     [dwAttachmentsNotCarriedNote] are pinned by full-string equality against the strings
 *     `build_report` builds, because "close enough" is how one product becomes two: a designer moving
 *     between the laptop and the handset must not be able to tell which client wrote a warning. A
 *     paraphrase here is a silent divergence that no compiler and no other test can see.
 *  3. **That neither sentence reaches the FILE.** Both are addressed to the designer on the day. An
 *     officer opening the .docx next month must not find a note about what was missing when it was
 *     made, which is the rule every warning on the export screen is under and the one thing a
 *     careless later edit would break by reaching for `builder.warn` out of habit.
 *
 * The logic under test is pure — [dwAttachmentsNamedButNotCarried] is a function of the draft and the
 * template, and [DwReportLosses] is a tally — which is the other reason it is worth pinning here
 * rather than only end to end: it is exactly the kind of thing that rots without anybody noticing.
 */
class ReportLossWarningsTest {

    // ── The registry, cut to the two shapes these warnings are about ─────────────────────────────

    /**
     * A gallery field, in the role the registry actually gives one.
     *
     * `GALLERY` and not `MEDIA`: `ReportRole` declares no MEDIA, every photograph field in
     * `stage_definitions` is GALLERY, and a fixture naming a role the server cannot emit tests the
     * port against nothing — the mistake [ReportEntityParityTest] records against itself.
     */
    private fun galleryField(key: String, label: String, tier: String = "BASIC") =
        FieldDto(key = key, label = label, type = "IMAGE_LIST", tier = tier, reportRole = "GALLERY")

    private fun singleImageField(key: String, label: String, tier: String = "BASIC") =
        FieldDto(key = key, label = label, type = "IMAGE", tier = tier, reportRole = "GALLERY")

    /** A FILE / AUDIO / VIDEO field — the three media types with no picture path. */
    private fun attachmentField(
        key: String,
        label: String,
        type: String,
        tier: String = "BASIC",
        role: String = "KEY_VALUE",
    ) = FieldDto(key = key, label = label, type = type, tier = tier, reportRole = role)

    private fun ids(vararg values: String): JsonElement =
        JsonArray(values.map { JsonPrimitive(it) })

    private fun schemaOf(vararg stages: StageDto) =
        SchemaResponse(version = "test", stages = stages.toList())

    private fun singletonStage(number: Int, key: String, title: String, vararg fields: FieldDto) =
        StageDto(
            number = number, key = key, title = title,
            entities = listOf(
                EntityDto(
                    key = key.lowercase() + "Singleton", cardinality = "SINGLETON", title = title,
                    fields = fields.toList(),
                )
            )
        )

    private fun collectionStage(number: Int, key: String, title: String, vararg fields: FieldDto) =
        StageDto(
            number = number, key = key, title = title,
            entities = listOf(
                EntityDto(
                    key = "piece", cardinality = "COLLECTION", title = "Pieces",
                    fields = fields.toList(),
                )
            )
        )

    private fun draftOf(vararg stages: Pair<String, StageDraft>) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = stages.toMap(),
    )

    private fun stageValues(stageKey: String, values: Map<String, JsonElement>) =
        stageKey to StageDraft(stageId = stageKey, values = values)

    private fun stageRows(stageKey: String, rows: List<DraftRow>) =
        stageKey to StageDraft(stageId = stageKey, rows = rows)

    /**
     * A template built here rather than taken from `TEMPLATES`.
     *
     * The six shipped templates are compile-time constants and only ONE of them caps a photograph at
     * all (COMPACT_SUMMARY's final-products section, at six). Testing through it would mean seven
     * photographs of one prototype per assertion and no way at all to exercise the pooled GALLERY
     * arm, which no shipped template caps. The sections declared here are the same
     * [TemplateSection] objects `TEMPLATES` is written in, so nothing about the render path is
     * simulated.
     */
    private fun templateOf(
        vararg sections: TemplateSection,
        name: String = "Compact summary",
        maxTier: DwTier = DwTier.ADVANCED,
    ) = ReportTemplate(
        id = "TEST_TEMPLATE",
        name = name,
        description = "A template that exists to have a cap.",
        sections = sections.toList(),
        maxTier = maxTier,
    )

    private fun planFor(template: ReportTemplate) = ReportPlan(
        template = template,
        meta = ReportMeta(
            title = "Barpali cluster",
            templateId = template.id,
            templateName = template.name,
            generatedAt = "2026-02-10T09:00:00Z",
        ),
        theme = ReportTheme(),
        settings = emptyMap(),
        warnings = emptyList(),
    )

    /**
     * Every media id resolves, so the cap has something to bite on.
     *
     * A fixed 800×600 ref rather than a real file: nothing here touches the filesystem, and an id
     * that failed to resolve would be counted by `onUnresolvedMedia` instead — a different warning
     * with a different sentence, and mixing the two is the confusion this pair was separated to
     * avoid.
     */
    private val everyImageResolves: (String) -> ImageRef? =
        { id -> ImageRef(source = "/dev/null/$id", widthPx = 800, heightPx = 600) }

    /**
     * Build, and hand back the document WITH whatever `onReportLosses` said.
     *
     * The sentinel is load-bearing: `onReportLosses` is contracted to fire on EVERY build, empty list
     * and all, because a screen that assigns it to a counter must be able to CLEAR that counter on
     * the export that lost nothing. A test that only asserted on the notes it expected would pass
     * happily if the callback were never invoked at all.
     */
    private val NEVER_CALLED = listOf("onReportLosses was never called")

    private fun build(
        schema: SchemaResponse,
        draft: WorkshopDraft,
        template: ReportTemplate,
        imageFor: (String) -> ImageRef? = everyImageResolves,
    ): Pair<ReportDocument, List<String>> {
        var notes: List<String> = NEVER_CALLED
        val document = buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = template.id,
            warnings = emptyList(),
            accent = "",
            imageFor = imageFor,
            plan = planFor(template),
            generatedAt = "2026-02-10T09:00:00Z",
            onReportLosses = { it -> notes = it },
        )
        return document to notes
    }

    // ── Reading the built document ───────────────────────────────────────────────────────────────

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is BulletListBlock -> block.items.joinToString("\n") { runText(it) }
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        is MetricRowBlock -> block.metrics.joinToString("\n") { (label, value, unit) -> "$label: $value $unit".trim() }
        else -> ""
    }

    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 1. The photograph cap
    // ════════════════════════════════════════════════════════════════════════════════════════════

    private fun cappedSingletonSchema() = schemaOf(
        singletonStage(
            13, "FINAL_PROTOTYPE_DOCUMENTATION", "Final prototype documentation",
            galleryField("photos", "Product photographs"),
        )
    )

    private fun fivePhotographs() = draftOf(
        stageValues(
            "FINAL_PROTOTYPE_DOCUMENTATION",
            mapOf("photos" to ids("m1", "m2", "m3", "m4", "m5")),
        )
    )

    @Test
    fun `photographs the cap kept out are counted, named and attributed to their stage`() {
        val (_, notes) = build(
            cappedSingletonSchema(),
            fivePhotographs(),
            templateOf(
                TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION", maxPhotos = 2),
            ),
        )

        // PINNED CHARACTER FOR CHARACTER against `build_report`'s own f-string. See this class's
        // header for why a paraphrase is a defect rather than a style difference.
        assertEquals(
            listOf(
                "3 photograph(s) recorded in this workshop did not fit Compact summary's " +
                    "photograph cap and are not in this file — stage 13. Generate the report with " +
                    "a template that prints every photograph to include them."
            ),
            notes,
        )
    }

    @Test
    fun `a template with no cap loses nothing and says nothing, and the callback still fires`() {
        val (_, notes) = build(
            cappedSingletonSchema(),
            fivePhotographs(),
            templateOf(TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION")),
        )

        // NOT `assertTrue(notes.isEmpty())` on its own: the sentinel is what proves the callback ran.
        // Five of the six shipped templates cap nothing, so this is the ordinary path, and it is the
        // path on which a stale count has to be cleared.
        assertEquals(emptyList<String>(), notes)
    }

    @Test
    fun `the cap sentence is never written into the document`() {
        val (document, notes) = build(
            cappedSingletonSchema(),
            fivePhotographs(),
            templateOf(
                TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION", maxPhotos = 2),
            ),
        )
        val printed = printedText(document)

        assertEquals(1, notes.size)
        // An officer opening this file next month must not read a note about what was missing on the
        // day the designer generated it. `unresolvedMediaNote` DOES go in the file, deliberately and
        // for a stated reason; these two must not, and `builder.warn` is the habit that would put
        // them there.
        assertFalse("the cap warning leaked into the document:\n$printed", printed.contains("did not fit"))
        assertFalse(printed.contains("photograph cap"))
        assertFalse(document.warnings.any { it.contains("photograph cap") })
    }

    @Test
    fun `the pooled gallery plate counts what its cap dropped too`() {
        // The ONE plate that crosses rows, and the only capped path that is not `imageGroupsOf`.
        // Two rows of two photographs each, capped at two: the last two are deleted outright.
        val schema = schemaOf(
            collectionStage(
                4, "CLUSTER_CRAFT_BACKGROUND", "Cluster and craft background",
                singleImageField("photo", "Motif"),
            )
        )
        val draft = draftOf(
            stageRows(
                "CLUSTER_CRAFT_BACKGROUND",
                listOf(
                    DraftRow(id = "piece#r1", values = mapOf("photo" to JsonPrimitive("m1"))),
                    DraftRow(id = "piece#r2", values = mapOf("photo" to JsonPrimitive("m2"))),
                    DraftRow(id = "piece#r3", values = mapOf("photo" to JsonPrimitive("m3"))),
                    DraftRow(id = "piece#r4", values = mapOf("photo" to JsonPrimitive("m4"))),
                ),
            )
        )

        val (_, notes) = build(
            schema, draft,
            templateOf(
                TemplateSection(
                    stageKey = "CLUSTER_CRAFT_BACKGROUND",
                    presentation = Presentation.GALLERY, maxPhotos = 2,
                ),
            ),
        )

        assertEquals(1, notes.size)
        assertTrue(notes[0], notes[0].startsWith("2 photograph(s) recorded in this workshop"))
        assertTrue(notes[0], notes[0].contains("— stage 4."))
    }

    @Test
    fun `two capped stages are named in registry order, not in the order the template prints them`() {
        val schema = schemaOf(
            singletonStage(4, "CLUSTER_CRAFT_BACKGROUND", "Cluster", galleryField("photos", "Motifs")),
            singletonStage(13, "FINAL_PROTOTYPE_DOCUMENTATION", "Prototypes", galleryField("photos", "Products")),
        )
        val draft = draftOf(
            stageValues("CLUSTER_CRAFT_BACKGROUND", mapOf("photos" to ids("a1", "a2", "a3"))),
            stageValues("FINAL_PROTOTYPE_DOCUMENTATION", mapOf("photos" to ids("b1", "b2"))),
        )

        // The template prints 13 BEFORE 4 — which is ordinary: `NARRATIVE_ORDER` is the reader's
        // order and the registry's numbering is the designer's. The sentence names stages by NUMBER,
        // so a reader scanning it expects them to climb; taking the tally's own insertion order
        // would print "stage 13, stage 4".
        val (_, notes) = build(
            schema, draft,
            templateOf(
                TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION", maxPhotos = 1),
                TemplateSection(stageKey = "CLUSTER_CRAFT_BACKGROUND", maxPhotos = 1),
            ),
        )

        assertEquals(1, notes.size)
        // 2 dropped at stage 4 and 1 at stage 13 — three in total, both stages named, ascending.
        assertTrue(notes[0], notes[0].startsWith("3 photograph(s)"))
        assertTrue(notes[0], notes[0].contains("— stage 4, stage 13."))
    }

    // ── The tally itself ────────────────────────────────────────────────────────────────────────

    private fun stagesOnly(vararg numbered: Pair<Int, String>) =
        schemaOf(*numbered.map { (n, k) -> singletonStage(n, k, k) }.toTypedArray())

    @Test
    fun `the tally sums per stage and ignores a count of none`() {
        val schema = stagesOnly(4 to "A", 13 to "B")
        val losses = DwReportLosses()

        losses.stageKey = "A"
        losses.notePhotographsOverCap(2)
        losses.notePhotographsOverCap(0)      // an uncapped plate reports zero on every pass
        losses.notePhotographsOverCap(3)
        losses.stageKey = "B"
        losses.notePhotographsOverCap(1)

        assertEquals(
            listOf("A" to 2 + 3, "B" to 1),
            losses.photographsOverCap(schema).map { (stage, count) -> stage.key to count },
        )
    }

    @Test
    fun `a loss with no stage set is filed nowhere rather than against the wrong stage`() {
        val schema = stagesOnly(4 to "A")
        val losses = DwReportLosses()

        // No `stageKey`, which is the state before the first stage section renders. Attributing this
        // to whatever happened to be rendered last is worse than dropping it: it sends a designer to
        // open a stage that is intact. Nothing in `main/` can reach this state — only a stage section
        // carries a collector — and that is what this pins.
        losses.notePhotographsOverCap(4)

        assertTrue(losses.photographsOverCap(schema).isEmpty())
    }

    @Test
    fun `a negative count cannot create a stage entry`() {
        val schema = stagesOnly(4 to "A")
        val losses = DwReportLosses()
        losses.stageKey = "A"
        losses.notePhotographsOverCap(-1)
        assertTrue(losses.photographsOverCap(schema).isEmpty())
    }

    // ── The sentence, on its own ────────────────────────────────────────────────────────────────

    @Test
    fun `no photographs dropped is null and not an empty bullet`() {
        assertNull(dwPhotographsOverCapNote(templateOf(), emptyList()))
    }

    @Test
    fun `more than four capped stages are elided exactly as the server elides them`() {
        val counted = listOf(1, 2, 3, 4, 5).map {
            singletonStage(it, "S$it", "Stage $it") to it
        }

        val note = dwPhotographsOverCapNote(templateOf(name = "Compact summary"), counted)

        // FOUR NAMED, THEN THE ELLIPSIS, THEN THE FULL STOP — including the doubled mark. The server
        // appends "…" to the LIST and the following sentence's full stop lands after it, so the two
        // clients produce this exact oddity together or the port has drifted.
        assertEquals(
            "15 photograph(s) recorded in this workshop did not fit Compact summary's photograph " +
                "cap and are not in this file — stage 1, stage 2, stage 3, stage 4…. Generate the " +
                "report with a template that prints every photograph to include them.",
            note,
        )
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 2. The attachments a report names and does not carry
    // ════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `every named file, recording and video is counted, once per file`() {
        val schema = schemaOf(
            singletonStage(
                1, "WORKSHOP_SETUP", "Workshop setup",
                attachmentField("sanctionDocument", "Sanction order", "FILE"),
                attachmentField("openingRecording", "Opening remarks", "AUDIO"),
                attachmentField("processVideo", "Process video", "VIDEO"),
            )
        )
        val draft = draftOf(
            stageValues(
                "WORKSHOP_SETUP",
                mapOf(
                    // Two documents in one field: COUNTED IN FILES AND NOT IN FIELDS, because a field
                    // holding three recordings is three things for somebody to go and find.
                    "sanctionDocument" to ids("f1", "f2"),
                    // A scalar, which is what a single-valued media field stores.
                    "openingRecording" to JsonPrimitive("a1"),
                    "processVideo" to ids("v1"),
                ),
            )
        )
        val template = templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP"))

        assertEquals(
            listOf("WORKSHOP_SETUP" to 4),
            dwAttachmentsNamedButNotCarried(schema, draft, template)
                .map { (stage, count) -> stage.key to count },
        )

        // Pinned against `build_report`'s f-string, character for character.
        assertEquals(
            "4 attached file(s) are named in this report but the files themselves are not inside " +
                "it — stage 1. A report file cannot carry a document, a recording or a video; send " +
                "them alongside it.",
            dwAttachmentsNotCarriedNote(dwAttachmentsNamedButNotCarried(schema, draft, template)),
        )
    }

    @Test
    fun `photographs are not counted, because photographs are the two types that ARE carried`() {
        val schema = schemaOf(
            singletonStage(
                13, "FINAL_PROTOTYPE_DOCUMENTATION", "Prototypes",
                galleryField("photos", "Product photographs"),
                singleImageField("hero", "Hero shot"),
            )
        )
        val draft = draftOf(
            stageValues(
                "FINAL_PROTOTYPE_DOCUMENTATION",
                mapOf("photos" to ids("m1", "m2"), "hero" to JsonPrimitive("m3")),
            )
        )

        // IMAGE and IMAGE_LIST are placed by `imagesOf`, so they are in the file and there is nothing
        // for the designer to send. Counting them would tell a designer to email photographs that are
        // already on the page.
        assertTrue(
            dwAttachmentsNamedButNotCarried(
                schema, draft, templateOf(TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION")),
            ).isEmpty()
        )
    }

    @Test
    fun `a HIDDEN attachment is not counted, because it is nowhere in the document to be about`() {
        val schema = schemaOf(
            singletonStage(
                1, "WORKSHOP_SETUP", "Workshop setup",
                attachmentField("internalScan", "Internal scan", "FILE", role = "HIDDEN"),
            )
        )
        val draft = draftOf(stageValues("WORKSHOP_SETUP", mapOf("internalScan" to ids("f1"))))

        assertTrue(
            dwAttachmentsNamedButNotCarried(
                schema, draft, templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP")),
            ).isEmpty()
        )
    }

    @Test
    fun `an attachment above the template's capture tier is left to the tier warning`() {
        val schema = schemaOf(
            singletonStage(
                1, "WORKSHOP_SETUP", "Workshop setup",
                attachmentField("sanctionDocument", "Sanction order", "FILE", tier = "ADVANCED"),
            )
        )
        val draft = draftOf(stageValues("WORKSHOP_SETUP", mapOf("sanctionDocument" to ids("f1"))))

        // The Basic-tier template does not print the field at all, so the file is not NAMED in the
        // report and this sentence would be false about it. The two warnings are disjoint by
        // construction and neither double-counts the other.
        assertTrue(
            dwAttachmentsNamedButNotCarried(
                schema, draft,
                templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP"), maxTier = DwTier.BASIC),
            ).isEmpty()
        )
        // The same field under a template that admits it IS named, and is counted.
        assertEquals(
            1,
            dwAttachmentsNamedButNotCarried(
                schema, draft,
                templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP"), maxTier = DwTier.ADVANCED),
            ).single().second,
        )
    }

    @Test
    fun `a stage the template does not print names nothing`() {
        val schema = schemaOf(
            singletonStage(1, "WORKSHOP_SETUP", "Setup", attachmentField("doc", "Order", "FILE")),
            singletonStage(5, "MARKET_ANALYSIS_DIRECTION", "Market", attachmentField("tape", "Interview", "AUDIO")),
        )
        val draft = draftOf(
            stageValues("WORKSHOP_SETUP", mapOf("doc" to ids("f1"))),
            stageValues("MARKET_ANALYSIS_DIRECTION", mapOf("tape" to ids("a1", "a2"))),
        )

        // The Photo catalogue carries neither the market stage nor its interview tapes; warning about
        // a file the document never mentions would send a designer chasing something no recipient is
        // missing.
        assertEquals(
            listOf("WORKSHOP_SETUP" to 1),
            dwAttachmentsNamedButNotCarried(
                schema, draft, templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP")),
            ).map { (stage, count) -> stage.key to count },
        )
    }

    @Test
    fun `attachments on a collection are counted across every row`() {
        val schema = schemaOf(
            collectionStage(
                14, "PROTOTYPE_ITERATIONS", "Iterations",
                attachmentField("clip", "Process clip", "VIDEO"),
            )
        )
        val draft = draftOf(
            stageRows(
                "PROTOTYPE_ITERATIONS",
                listOf(
                    DraftRow(id = "piece#r1", values = mapOf("clip" to ids("v1"))),
                    DraftRow(id = "piece#r2", values = mapOf("clip" to ids("v2", "v3"))),
                    DraftRow(id = "piece#r3", values = emptyMap()),
                ),
            )
        )

        assertEquals(
            3,
            dwAttachmentsNamedButNotCarried(
                schema, draft, templateOf(TemplateSection(stageKey = "PROTOTYPE_ITERATIONS")),
            ).single().second,
        )
    }

    @Test
    fun `nothing attached is null and not an empty bullet`() {
        assertNull(dwAttachmentsNotCarriedNote(emptyList()))
    }

    @Test
    fun `more than four stages of attachments are elided exactly as the server elides them`() {
        val counted = listOf(1, 2, 3, 4, 5).map { singletonStage(it, "S$it", "Stage $it") to 1 }

        assertEquals(
            "5 attached file(s) are named in this report but the files themselves are not inside " +
                "it — stage 1, stage 2, stage 3, stage 4…. A report file cannot carry a document, " +
                "a recording or a video; send them alongside it.",
            dwAttachmentsNotCarriedNote(counted),
        )
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 3. Both of them, through the builder, in the server's order
    // ════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `both losses reach the caller in build_report's order and neither reaches the file`() {
        val schema = schemaOf(
            singletonStage(
                13, "FINAL_PROTOTYPE_DOCUMENTATION", "Final prototype documentation",
                galleryField("photos", "Product photographs"),
                attachmentField("spec", "Specification sheet", "FILE"),
            )
        )
        val draft = draftOf(
            stageValues(
                "FINAL_PROTOTYPE_DOCUMENTATION",
                mapOf("photos" to ids("m1", "m2", "m3"), "spec" to ids("f1")),
            )
        )

        val (document, notes) = build(
            schema, draft,
            templateOf(TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION", maxPhotos = 1)),
        )

        assertEquals(2, notes.size)
        // Over-cap first, attachments second — `build_report`'s order. Two call sites each choosing
        // for themselves is how one workshop comes to have two differently-ordered warning lists.
        assertTrue(notes[0], notes[0].contains("photograph cap"))
        assertTrue(notes[1], notes[1].contains("attached file(s) are named in this report"))

        val printed = printedText(document)
        // THE FILE STILL SAYS AN ATTACHMENT EXISTS — that is honest and it stays. What must not be in
        // it is the note about what was missing on the day.
        assertTrue("the attachment is not named in the file:\n$printed", printed.contains("1 document attached"))
        assertFalse(printed.contains("send them alongside it"))
        assertFalse(printed.contains("did not fit"))
    }
}
