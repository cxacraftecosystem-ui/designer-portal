package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ImageBlock
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.MetricRowBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOW ONE RECORD IS LAID OUT ON THE HANDSET, AGAINST `ReportBuilder._render_narrative`.
 *
 * [ReportDocumentTest] asserts that the words a designer typed are IN the file, deliberately and
 * correctly saying nothing about order or structure. That is why it passed through five divergences
 * at once, every one of them in `renderEntity`/`renderCollection`, and every one of them producing
 * a document that is legible, complete-looking and NOT the document the office generates from the
 * same workshop:
 *
 *   1. all 98 RICH_TEXT fields printed with no label, so eight consecutive answers on stage 2 ran
 *      together as anonymous prose where the server prints eight numbered sub-headings;
 *   2. an unfilled REQUIRED field printed nothing, where `_printable` substitutes "Not recorded." —
 *      in the body AND in a table cell, so the phone's copy understated incompleteness while the
 *      completeness annexure in the same file stated it;
 *   3. the blocks came out in this file's own order (prose, metrics, grid, photographs) rather than
 *      the server's (grid, prose, bullets, metrics, photographs), with a card's photographs last
 *      where `_render_cards` puts them first;
 *   4. a table drew EVERY TABLE_COLUMN, ignoring `_table_columns`' `[:6]`, and its per-row leftover
 *      block keyed on the ROLE rather than on membership so the overflow could not land there
 *      either;
 *   5. `imagesOf` had only the first of `_images`' two passes, so a record whose photograph belongs
 *      to the row a REF points at printed no picture at all.
 *
 * These assertions therefore ARE about structure and order, which is the opposite of
 * [ReportDocumentTest]'s stated rule — and on purpose. The thing that must never regress here is not
 * "the words are present" but "the two copies of one workshop are the same document": same heading
 * tree, same contents page, same section numbering, same number of plates.
 */
class ReportEntityParityTest {

    // ── The registry, cut to the shapes that diverged ────────────────────────────────────────────

    private fun richField(key: String, label: String, role: String, required: Boolean = false) =
        FieldDto(key = key, label = label, type = "RICH_TEXT", reportRole = role, required = required)

    private fun textField(key: String, label: String, role: String = "KEY_VALUE", required: Boolean = false) =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = role, required = required)

    /**
     * A photograph field, in the role the registry actually gives one.
     *
     * IT USED TO SAY `reportRole = "MEDIA"`, AND THAT IS HOW THE REGRESSION GOT PAST THIS FILE.
     * `ReportRole` declares no MEDIA; every photograph field in `stage_definitions` is GALLERY (the
     * `photos(...)` helper emits it, and the sketch entity writes it by hand). MEDIA is an unknown
     * role, so it exercised the deliberate unknown-role degrade instead of the branch the registry
     * takes — and the one REQUIRED GALLERY field in the registry, `sketch.image`, had no fixture in
     * this file at all. A fixture naming a role the server cannot emit tests the port against nothing.
     */
    private fun imageField(key: String, label: String, required: Boolean = false) =
        FieldDto(
            key = key, label = label, type = "IMAGE",
            reportRole = "GALLERY", required = required,
        )

    private fun refField(
        key: String,
        label: String,
        model: String,
        role: String = "KEY_VALUE",
        /** The server's own `REFERENCE_HYDRATION` for this field, as `field_to_dict` publishes it. */
        hydration: Map<String, String> = emptyMap(),
    ) = FieldDto(
        key = key, label = label, type = "REF", refModel = model, reportRole = role,
        refHydration = hydration,
    )

    /** A FILE / AUDIO / VIDEO field - the three media types with no picture path. */
    private fun attachmentField(key: String, label: String, type: String, required: Boolean = false) =
        FieldDto(key = key, label = label, type = type, reportRole = "KEY_VALUE", required = required)

    private fun metricField(key: String, label: String, unit: String = "") =
        FieldDto(key = key, label = label, type = "INT", reportRole = "METRIC", unit = unit)

    /** `{"blocks":[{"kind":…,"spans":[{"text":…}]}]}` — the shape `rich_text.to_json` writes. */
    private fun rich(vararg blocks: Pair<String, String>): JsonElement = buildJsonObject {
        putJsonArray("blocks") {
            blocks.forEach { (kind, text) ->
                add(
                    buildJsonObject {
                        put("kind", kind)
                        putJsonArray("spans") { add(buildJsonObject { put("text", text) }) }
                    }
                )
            }
        }
    }

    private fun schemaOf(vararg stages: StageDto) =
        SchemaResponse(version = "test", stages = stages.toList())

    private fun draftOf(
        stageKey: String,
        values: Map<String, JsonElement> = emptyMap(),
        rows: List<DraftRow> = emptyList(),
        extra: Map<String, StageDraft> = emptyMap(),
    ) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(stageKey to StageDraft(stageId = stageKey, values = values, rows = rows)) + extra,
    )

    /**
     * Every media id resolves, so the ordering assertions can see the photographs.
     *
     * A fixed 800×600 ref rather than a real file: nothing here touches the filesystem, and the
     * writers are not under test — where the blocks SIT is.
     */
    private val everyImageResolves: (String) -> ImageRef? =
        { id -> ImageRef(source = "/dev/null/$id", widthPx = 800, heightPx = 600) }

    private fun build(
        schema: SchemaResponse,
        draft: WorkshopDraft,
        templateId: String = "DCH_STANDARD",
        imageFor: (String) -> ImageRef? = { null },
    ): ReportDocument = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = draft.workshopId,
        templateId = templateId,
        warnings = emptyList(),
        accent = "",
        imageFor = imageFor,
        generatedAt = "2026-02-10T09:00:00Z",
    )

    // ── Reading the built document ───────────────────────────────────────────────────────────────

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is BulletListBlock -> block.items.joinToString("\n") { runText(it) }
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        // Included where [ReportDocumentTest]'s reader leaves it out, because a METRIC field is one
        // of the four passes under test and a reader that cannot see it cannot prove it survived.
        is MetricRowBlock -> block.metrics.joinToString("\n") { (label, value, unit) -> "$label: $value $unit".trim() }
        else -> ""
    }

    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    /** Every heading in the file, at any level, without its section number. */
    private fun headings(document: ReportDocument): List<String> =
        document.blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs).trim() }

    /**
     * The block kinds a document is made of, in order, as a compact string.
     *
     * An ordering assertion has to name the ORDER, and a list of class names is the honest way to
     * write it down: a diff on failure shows exactly which block moved.
     */
    private fun shape(document: ReportDocument): List<String> =
        document.blocks.map { it::class.simpleName.orEmpty() }

    private fun indexOfFirst(document: ReportDocument, kind: String): Int =
        shape(document).indexOfFirst { it == kind }

    // ── 1. Every printed field carries its label ─────────────────────────────────────────────────

    /** Stage 2's real shape, cut down: several NARRATIVE answers and one BULLETS, all RICH_TEXT. */
    private fun stageTwoSchema() = schemaOf(
        StageDto(
            number = 2, key = "INTRODUCTORY_ADMIN_DOCUMENTATION",
            title = "Introductory & Administrative Documentation",
            entities = listOf(
                EntityDto(
                    key = "introduction", cardinality = "SINGLETON", title = "Introduction",
                    fields = listOf(
                        richField("acknowledgement", "Acknowledgement", "NARRATIVE"),
                        richField("purpose", "Purpose", "NARRATIVE"),
                        richField("programmeObjectives", "Programme objectives", "BULLETS"),
                    ),
                )
            )
        )
    )

    @Test
    fun `every narrative answer prints under its own field label`() {
        // Short answers, so both take the `"Label:"` lead-in rather than the heading — the branch
        // `_render_narrative` picks when the flattened text is 160 characters or fewer and the field
        // produced one block.
        val document = build(
            stageTwoSchema(),
            draftOf(
                "INTRODUCTORY_ADMIN_DOCUMENTATION",
                values = mapOf(
                    "acknowledgement" to rich("PARAGRAPH" to "The weavers gave a fortnight."),
                    "purpose" to rich("PARAGRAPH" to "To revive the bandha tradition."),
                ),
            ),
        )
        val printed = printedText(document)

        // BEFORE: the two paragraphs printed one after the other with nothing between them, so an
        // officer could not tell which pro-forma question either answered.
        assertTrue("the acknowledgement is unlabelled:\n$printed", printed.contains("Acknowledgement:"))
        assertTrue("the purpose is unlabelled:\n$printed", printed.contains("Purpose:"))
        assertTrue(printed.contains("The weavers gave a fortnight."))
        assertTrue(printed.contains("To revive the bandha tradition."))
    }

    @Test
    fun `a long narrative answer takes a sub-heading instead of a lead-in`() {
        // `len(text) > 160 or len(blocks) > 1` — the exact rule, so the heading tree and therefore
        // the contents page match the office's copy.
        val long = "The cluster's weavers gave a fortnight of their time to this workshop, and the " +
            "opening session ran over by two hours because every household wanted to show the " +
            "sarees they had woven for the last co-operative order."
        assertTrue("the fixture must exceed the 160-character rule", long.length > 160)

        val document = build(
            stageTwoSchema(),
            draftOf(
                "INTRODUCTORY_ADMIN_DOCUMENTATION",
                values = mapOf("acknowledgement" to rich("PARAGRAPH" to long)),
            ),
        )

        assertTrue(
            "a long answer must head its own sub-section: ${headings(document)}",
            headings(document).contains("Acknowledgement"),
        )
        assertFalse(
            "and must NOT also carry the short form's lead-in",
            printedText(document).contains("Acknowledgement:"),
        )
    }

    @Test
    fun `a bulleted answer is always headed, however short it is`() {
        val document = build(
            stageTwoSchema(),
            draftOf(
                "INTRODUCTORY_ADMIN_DOCUMENTATION",
                values = mapOf(
                    "programmeObjectives" to rich(
                        "BULLET_ITEM" to "Re-establish the natural indigo vat",
                        "BULLET_ITEM" to "Link the cluster to two export buyers",
                    )
                ),
            ),
        )

        // `_render_narrative` emits the heading for a BULLETS field unconditionally — a list with no
        // title above it reads as a continuation of the paragraph before it.
        assertTrue(
            "the bulleted answer is unheaded: ${headings(document)}",
            headings(document).contains("Programme objectives"),
        )
        assertEquals(
            listOf("Re-establish the natural indigo vat", "Link the cluster to two export buyers"),
            document.blocks.filterIsInstance<BulletListBlock>().single().items.map(::runText),
        )
    }

    // ── 2. An unfilled REQUIRED field says so ────────────────────────────────────────────────────

    @Test
    fun `an unfilled required field prints Not recorded and an unfilled optional one prints nothing`() {
        val schema = schemaOf(
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "detail", cardinality = "SINGLETON", title = "Prototype development",
                        fields = listOf(
                            textField("materials", "Materials", required = true),
                            textField("remarks", "Remarks"),
                            textField("supervisor", "Supervisor"),
                        ),
                    )
                )
            )
        )
        val printed = printedText(
            build(
                schema,
                draftOf("PROTOTYPE_DEVELOPMENT", values = mapOf("supervisor" to JsonPrimitive("B. Meher"))),
            )
        )

        // A missing REQUIRED field is information — it says the record is incomplete. A missing
        // optional one is not, and printing the note for every unfilled Advanced field would bury
        // the report in negatives.
        assertTrue("the required gap is invisible:\n$printed", printed.contains("Materials: Not recorded."))
        assertFalse("an optional gap must stay silent:\n$printed", printed.contains("Remarks"))
        assertTrue(printed.contains("Supervisor: B. Meher"))
    }

    @Test
    fun `an unfilled required TABLE_COLUMN prints the note in its cell, not a blank`() {
        // FOLLOW_UP and not PROTOTYPE_DEVELOPMENT: DCH_STANDARD overrides stage 13's presentation to
        // CARDS, so a table never forms there and the assertion would test nothing. Stage 22 is left
        // on AUTO, which resolves to TABLE the moment the registry names a column.
        val schema = schemaOf(
            StageDto(
                number = 22, key = "FOLLOW_UP", title = "Follow up",
                entities = listOf(
                    EntityDto(
                        key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Code", role = "TABLE_COLUMN"),
                            textField("materials", "Materials", role = "TABLE_COLUMN", required = true),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "FOLLOW_UP",
                rows = listOf(
                    DraftRow(id = "followUp#row-1", values = mapOf("code" to JsonPrimitive("PT-01"))),
                ),
            ),
        )

        // `_cell_runs`'s docstring has always warned that routing an empty rich value through
        // `plain_runs` "would replace the note with a blank cell — turning a visible gap into an
        // invisible one". It was a true statement about a note this path never produced.
        val table = document.blocks.filterIsInstance<TableBlock>().single()
        assertEquals(
            listOf("PT-01", "Not recorded."),
            table.rows.single().map(::runText),
        )
    }

    @Test
    fun `an unfilled required rich-text field is not swallowed by the empty-document check`() {
        // The narrow trap: a RICH_TEXT value that exists but is textually empty must fall through to
        // the note rather than to `toReportBlocks`, or the substitution vanishes in the one type
        // that carries 98 of the registry's printed fields.
        val schema = schemaOf(
            StageDto(
                number = 2, key = "INTRODUCTORY_ADMIN_DOCUMENTATION", title = "Introduction",
                entities = listOf(
                    EntityDto(
                        key = "introduction", cardinality = "SINGLETON", title = "Introduction",
                        fields = listOf(
                            richField("purpose", "Purpose", "NARRATIVE", required = true),
                            textField("agency", "Agency"),
                        ),
                    )
                )
            )
        )
        val printed = printedText(
            build(
                schema,
                draftOf(
                    "INTRODUCTORY_ADMIN_DOCUMENTATION",
                    values = mapOf(
                        "purpose" to rich("PARAGRAPH" to ""),
                        "agency" to JsonPrimitive("Sambalpuri Bastralaya"),
                    ),
                ),
            )
        )

        assertTrue("the required narrative gap is invisible:\n$printed", printed.contains("Purpose: Not recorded."))
    }

    // ── 3. The server's block order ──────────────────────────────────────────────────────────────

    @Test
    fun `a stage singleton prints grid, then prose, then bullets, then metrics, then photographs`() {
        val schema = schemaOf(
            StageDto(
                number = 6, key = "EXISTING_PRODUCTS_BASELINE", title = "Baseline",
                entities = listOf(
                    EntityDto(
                        key = "baseline", cardinality = "SINGLETON", title = "Baseline",
                        fields = listOf(
                            // Declared prose-first, so a single walk of the field list CANNOT
                            // produce the server's order by accident.
                            richField("background", "Cluster background", "NARRATIVE"),
                            richField("gaps", "Gaps observed", "BULLETS"),
                            metricField("looms", "Looms in use"),
                            textField("village", "Village"),
                            imageField("photo", "Baseline photograph"),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "EXISTING_PRODUCTS_BASELINE",
                values = mapOf(
                    "background" to rich("PARAGRAPH" to "Fourteen households weave for the co-operative."),
                    "gaps" to rich("BULLET_ITEM" to "No natural dye vat"),
                    "looms" to JsonPrimitive(14),
                    "village" to JsonPrimitive("Barpali"),
                    "photo" to JsonPrimitive("media-1"),
                ),
            ),
            imageFor = everyImageResolves,
        )

        val grid = indexOfFirst(document, "KeyValueBlock")
        val prose = document.blocks.indexOfFirst { it is ParagraphBlock && textOf(it).startsWith("Cluster background") }
        val bullets = indexOfFirst(document, "BulletListBlock")
        val metrics = indexOfFirst(document, "MetricRowBlock")
        val photo = indexOfFirst(document, "ImageBlock")

        assertTrue("every block must be present: ${shape(document)}", listOf(grid, prose, bullets, metrics, photo).none { it < 0 })
        assertTrue("the key-value grid comes first: ${shape(document)}", grid < prose)
        assertTrue("then the prose: ${shape(document)}", prose < bullets)
        assertTrue("then the bullets: ${shape(document)}", bullets < metrics)
        assertTrue("then the metrics, and the photographs last: ${shape(document)}", metrics < photo)
    }

    @Test
    fun `a card puts its photographs under the heading and before its fields`() {
        // `_render_cards` places the pictures immediately under the row heading and BEFORE any
        // field, which is the opposite way round from a stage singleton. A card IS its photograph.
        val schema = schemaOf(
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", cardinality = "COLLECTION", title = "Prototype",
                        labelField = "code",
                        fields = listOf(
                            // No TABLE_COLUMN anywhere, so AUTO falls through to CARDS.
                            textField("code", "Code"),
                            richField("processSummary", "Process summary", "NARRATIVE"),
                            imageField("photo", "Prototype photograph"),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "PROTOTYPE_DEVELOPMENT",
                rows = listOf(
                    DraftRow(
                        id = "prototype#row-1",
                        values = mapOf(
                            "code" to JsonPrimitive("PT-01"),
                            "processSummary" to rich("PARAGRAPH" to "Woven on loom 14 over three days."),
                            "photo" to JsonPrimitive("media-1"),
                        ),
                    )
                ),
            ),
            imageFor = everyImageResolves,
        )

        val photo = indexOfFirst(document, "ImageBlock")
        val grid = indexOfFirst(document, "KeyValueBlock")
        assertTrue("the card must carry both: ${shape(document)}", photo >= 0 && grid >= 0)
        assertTrue("a card's photograph goes first: ${shape(document)}", photo < grid)
    }

    // ── 4. Six columns, and the overflow underneath ──────────────────────────────────────────────

    /** Stage 22's real shape: seven TABLE_COLUMN fields on one collection. */
    private fun sevenColumnSchema() = schemaOf(
        StageDto(
            number = 22, key = "FOLLOW_UP", title = "Follow up",
            entities = listOf(
                EntityDto(
                    key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                    labelField = "product",
                    fields = (1..7).map { n ->
                        textField("col$n", "Column $n", role = "TABLE_COLUMN")
                    },
                )
            )
        )
    )

    private fun sevenColumnRow() = DraftRow(
        id = "followUp#row-1",
        values = (1..7).associate { n -> "col$n" to (JsonPrimitive("value $n") as JsonElement) },
    )

    @Test
    fun `a table is capped at six columns, as the server caps it`() {
        val document = build(sevenColumnSchema(), draftOf("FOLLOW_UP", rows = listOf(sevenColumnRow())))
        val table = document.blocks.filterIsInstance<TableBlock>().single()

        // Six on A4 is about the limit before a cell is too narrow to hold a craft name. Three live
        // collections in the bundled registry declare seven, all at BASIC or STANDARD tier, so no
        // template's cap trims them under seven either.
        assertEquals("`_table_columns` returns columns[:6]", 6, table.columns.size)
        assertEquals(
            (1..6).map { "Column $it" },
            table.columns.map { it.header },
        )
    }

    @Test
    fun `the seventh column is carried under the row rather than dropped`() {
        val printed = printedText(
            build(sevenColumnSchema(), draftOf("FOLLOW_UP", rows = listOf(sevenColumnRow())))
        )

        // `_render_table` prints the overflow under each row via `_render_narrative(skip=column_keys)`.
        // Android's leftover set used to key on the ROLE, so the overflow could not reach the per-row
        // block even once the cap existed — capping alone would have been a silent drop.
        assertTrue("the overflow column is missing from the file:\n$printed", printed.contains("Column 7: value 7"))
        // And the six that WERE drawn must not be repeated underneath.
        assertFalse("a drawn column must not print twice:\n$printed", printed.contains("Column 1: value 1"))
    }

    @Test
    fun `a media TABLE_COLUMN prints as a photograph instead of vanishing`() {
        /*
          LATENT TODAY AND FIXED ANYWAY — zero fields in the bundled registry are both TABLE_COLUMN
          and media-typed. Such a field was excluded from `columns` by `!isMedia` AND from the
          leftovers by the role test, so it was dropped from the document entirely: not a layout
          difference, a silent loss of a field somebody filled in.

          AND THE SERVER NOW AGREES ABOUT THE COLUMN. This comment used to record a live divergence
          — "`_table_columns` applies no media filter, so the server would make this a table column"
          — and say the agreement had to be made server-side first. It has been: `_table_columns`
          filters `not f.type.is_media`, so both surfaces draw ONE column here and the assertion
          below is a parity assertion rather than a pinned difference. What is still divergent is the
          column WIDTHS on five entities, which is a separate and deliberate thing.
        */
        val schema = schemaOf(
            StageDto(
                number = 22, key = "FOLLOW_UP", title = "Follow up",
                entities = listOf(
                    EntityDto(
                        key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                        labelField = "product",
                        fields = listOf(
                            textField("product", "Product", role = "TABLE_COLUMN"),
                            FieldDto(
                                key = "shot", label = "Shop shelf", type = "IMAGE",
                                reportRole = "TABLE_COLUMN",
                            ),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "FOLLOW_UP",
                rows = listOf(
                    DraftRow(
                        id = "followUp#row-1",
                        values = mapOf(
                            "product" to JsonPrimitive("Bandha runner"),
                            "shot" to JsonPrimitive("media-1"),
                        ),
                    )
                ),
            ),
            imageFor = everyImageResolves,
        )

        assertEquals(1, document.blocks.filterIsInstance<TableBlock>().single().columns.size)
        assertTrue(
            "the media column vanished from the document: ${shape(document)}",
            document.blocks.any { it is ImageBlock || it is ImageGridBlock },
        )
    }

    // ── 5. The photograph of the record a REF points at ──────────────────────────────────────────

    @Test
    fun `a record with no photograph of its own borrows the one on the record it references`() {
        /*
          `_images` PASS TWO, which Android had no counterpart for. `prototype.sketchRef` points at a
          sketch row whose own photographs are in stage 11 of the same draft, so this is reachable
          with no network at all — and the office's copy prints that sketch under each prototype
          while the phone's printed nothing.
        */
        val schema = schemaOf(
            StageDto(
                number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development",
                entities = listOf(
                    EntityDto(
                        key = "sketch", cardinality = "COLLECTION", title = "Sketch",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Sketch code"),
                            imageField("sketchImage", "Sketch"),
                        ),
                    )
                )
            ),
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", cardinality = "COLLECTION", title = "Prototype",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Code"),
                            refField("sketchRef", "Sketch", "DwSketch"),
                        ),
                    )
                )
            ),
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "SKETCH_DEVELOPMENT" to StageDraft(
                    stageId = "SKETCH_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "sketch#kzzq1p8n4m2wd7xr",
                            values = mapOf(
                                "code" to JsonPrimitive("SK-04"),
                                "sketchImage" to JsonPrimitive("media-sketch-4"),
                            ),
                        )
                    ),
                ),
                "PROTOTYPE_DEVELOPMENT" to StageDraft(
                    stageId = "PROTOTYPE_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "prototype#row-1",
                            values = mapOf(
                                "code" to JsonPrimitive("PT-01"),
                                "sketchRef" to JsonPrimitive("kzzq1p8n4m2wd7xr"),
                            ),
                        )
                    ),
                ),
            ),
        )

        val document = build(schema, draft, imageFor = everyImageResolves)
        val borrowed = document.blocks.filterIsInstance<ImageBlock>()
            .filter { it.image.source.endsWith("media-sketch-4") }

        // Twice: once under the sketch's own card in stage 11, once under the prototype that cites
        // it in stage 13. The second one is the whole finding.
        assertEquals(
            "the prototype printed no picture at all: ${shape(document)}",
            2,
            borrowed.size,
        )
        // THE REFERENCE'S OWN LABEL, because the field's label is the relationship and not the
        // subject: "Sketch" under a photograph is a category, "SK-04" is a caption.
        assertTrue(
            "the borrowed plate must be captioned with the referenced record: ${borrowed.map { it.caption }}",
            borrowed.any { it.caption == "SK-04" },
        )
    }

    @Test
    fun `a photograph already on the row is not printed twice by the reference pass`() {
        // Hydration copies an artisan's picture onto `participant.photo` at save time, so the
        // `artisanRef` beside it can resolve to the very same media row. Without the dedup map that
        // participant's photograph prints twice on one card.
        val schema = schemaOf(
            StageDto(
                number = 3, key = "WORKSHOP_PLAN_PARTICIPANTS_OPENING", title = "Participants",
                entities = listOf(
                    EntityDto(
                        key = "roster", cardinality = "COLLECTION", title = "Roster entry",
                        labelField = "who",
                        fields = listOf(
                            textField("who", "Name"),
                            imageField("photo", "Photograph"),
                        ),
                    ),
                    EntityDto(
                        key = "participant", cardinality = "COLLECTION", title = "Participant",
                        labelField = "name",
                        fields = listOf(
                            textField("name", "Name"),
                            imageField("photo", "Photograph"),
                            refField("artisanRef", "Artisan", "Artisan"),
                        ),
                    ),
                )
            )
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "WORKSHOP_PLAN_PARTICIPANTS_OPENING" to StageDraft(
                    stageId = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                    rows = listOf(
                        DraftRow(
                            id = "roster#kzzq1p8n4m2wd7xr",
                            values = mapOf(
                                "who" to JsonPrimitive("Bhikari Meher"),
                                "photo" to JsonPrimitive("media-artisan-1"),
                            ),
                        ),
                        DraftRow(
                            id = "participant#row-1",
                            values = mapOf(
                                "name" to JsonPrimitive("Bhikari Meher"),
                                // Hydration already copied the artisan's picture onto the row.
                                "photo" to JsonPrimitive("media-artisan-1"),
                                "artisanRef" to JsonPrimitive("kzzq1p8n4m2wd7xr"),
                            ),
                        ),
                    ),
                )
            ),
        )

        val document = build(schema, draft, imageFor = everyImageResolves)
        val onTheParticipantCard = document.blocks
            .filterIsInstance<ImageBlock>()
            .count { it.image.source.endsWith("media-artisan-1") }

        // One per record that holds it — the roster row and the participant row — and NOT three.
        assertEquals(
            "the reference pass duplicated a picture the row already carried: ${shape(document)}",
            2,
            onTheParticipantCard,
        )
    }


    // -- 6. The media split: three types that print a sentence, two that print a plate -----------

    /**
     * `format_value`'s MEDIA BRANCH, which this file compensated for by dropping media entirely.
     *
     * `_images` places IMAGE and IMAGE_LIST and nothing else, so the registry's eight FILE, five
     * AUDIO and four VIDEO fields had NO path to paper at all until the server grew a count and a
     * noun for them. A designer attached the ministry's sanction order at stage 1 and the .docx the
     * officer received did not mention that a sanction order existed - while the tier warning named
     * the field as one to "generate the report with a template that captures every tier to include",
     * which no template could do. `renderEntity`'s `printable` walk skipped every media-typed field
     * under a comment asserting the server did the same; it never did.
     *
     * The nouns are asserted by value because they are `format_value`'s exact words. A different
     * word here is a different sentence in one of the two copies of one workshop's report.
     */
    @Test
    fun `a document, a recording and a video print a count and a noun where a photograph prints neither`() {
        val schema = schemaOf(
            StageDto(
                number = 1, key = "WORKSHOP_SETUP", title = "Setup",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Setup",
                        fields = listOf(
                            attachmentField("sanctionOrder", "Sanction order", "FILE"),
                            attachmentField("interviewClips", "Interview recordings", "AUDIO"),
                            attachmentField("processClip", "Process video", "VIDEO"),
                            attachmentField("consentForm", "Consent form", "FILE", required = true),
                            imageField("venuePhoto", "Venue photograph"),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "WORKSHOP_SETUP",
                values = mapOf(
                    "sanctionOrder" to JsonPrimitive("media-file-1"),
                    "interviewClips" to JsonArray(
                        listOf(JsonPrimitive("media-audio-1"), JsonPrimitive("media-audio-2"))
                    ),
                    "processClip" to JsonPrimitive("media-video-1"),
                    "venuePhoto" to JsonPrimitive("media-photo-1"),
                ),
            ),
            imageFor = everyImageResolves,
        )
        val printed = printedText(document)

        assertTrue("the sanction order is unmentioned:\n" + printed, printed.contains("Sanction order: 1 document attached"))
        assertTrue("the plural noun:\n" + printed, printed.contains("Interview recordings: 2 recordings attached"))
        assertTrue("VIDEO has its own noun:\n" + printed, printed.contains("Process video: 1 video attached"))

        // NOT A FILENAME AND NEVER AN ID. The stored value is a media id and neither builder may
        // query for the name the designer uploaded - this one has no network when it matters.
        assertFalse("a media id must not reach paper:\n" + printed, printed.contains("media-file-1"))
        assertFalse(printed.contains("media-audio-1"))

        // A PICTURE STILL SAYS NOTHING IN THE GRID; it is placed as a plate by `imagesOf`, and it
        // must still BE placed - the split must not have made a photograph silent everywhere.
        assertFalse("a photograph must not print as text:\n" + printed, printed.contains("Venue photograph:"))
        assertTrue(
            "the photograph must still be placed: " + shape(document),
            document.blocks.any { it is ImageBlock || it is ImageGridBlock },
        )

        // AND A REQUIRED ATTACHMENT THAT WAS NEVER MADE IS SAID, exactly as `_printable` says it for
        // every other role. This is the substitution the old `isMedia` skip put out of reach.
        assertTrue("an unattached required document must be reported:\n" + printed, printed.contains("Consent form: Not recorded."))
    }

    /**
     * A REQUIRED PHOTOGRAPH THAT WAS TAKEN MUST NOT BE CALLED MISSING BESIDE ITSELF.
     *
     * THE REGRESSION THIS PINS, IN THE ORDER IT HAPPENED. `printable` used to skip every media-typed
     * field; removing that skip was right, because it was withholding "1 document attached" for the
     * registry's seventeen FILE/AUDIO/VIDEO fields. But this port's `pairs` is a NEGATIVE role test
     * and it named only three roles, where `_render_narrative` asks POSITIVELY for exactly three —
     * KEY_VALUE, COVER_FIELD, TABLE_COLUMN. GALLERY therefore fell into the grid on the handset and
     * reaches `_printable` on no path at all at the office. [displayValue] answers "" for an IMAGE,
     * as it must, so the `required && showEmptyNote` arm fired: every sketch whose photograph the
     * designer HAD taken printed "Sketch image: Not recorded." in the grid, one line under the plate
     * `imagesOf` had just placed. `stage_definitions` writes that field
     * `f("image", "Sketch image", IMG, B, required=True, report_role=GALLERY)`, so this is the
     * bundled registry's own shape and not a hypothetical, and the office's copy printed neither
     * line.
     *
     * THE SECOND HALF IS THE HEADING, AND IT IS WHY THIS TEST BUILDS TWO DRAFTS. A required GALLERY
     * field with no photograph now prints nothing on either surface — so a collection row with
     * nothing else left to say must not be given a numbered sub-heading over the silence.
     * `_render_table`'s `has_extra` leaves GALLERY out of its role set for exactly that reason.
     * Asserting only the first half would trade a wrong sentence for a bare heading.
     */
    @Test
    fun `a required photograph that was taken prints its plate and no note`() {
        val schema = schemaOf(
            StageDto(
                number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development",
                entities = listOf(
                    EntityDto(
                        key = "sketch", cardinality = "COLLECTION", title = "Sketch",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Sketch code", role = "TABLE_COLUMN"),
                            imageField("image", "Sketch image", required = true),
                        ),
                    )
                )
            )
        )
        val taken = build(
            schema,
            draftOf(
                "SKETCH_DEVELOPMENT",
                rows = listOf(
                    DraftRow(
                        id = "sketch#1",
                        values = mapOf(
                            "code" to JsonPrimitive("SK-01"),
                            "image" to JsonPrimitive("media-sketch-1"),
                        ),
                    )
                ),
            ),
            imageFor = everyImageResolves,
        )
        val printedTaken = printedText(taken)
        assertTrue(
            "the photograph was not placed at all: " + shape(taken),
            taken.blocks.any { it is ImageBlock || it is ImageGridBlock },
        )
        assertFalse(
            "an attached photograph is called missing beside itself:\n" + printedTaken,
            printedTaken.contains("Sketch image: Not recorded."),
        )
        // And not under any other wording either: the whole ROLE is out of the grid, not just the
        // note. A GALLERY field reaches `_printable` on no server path, filled or empty.
        assertFalse(
            "a GALLERY field reached the key-value grid:\n" + printedTaken,
            printedTaken.contains("Sketch image:"),
        )

        // NO PHOTOGRAPH: still no sentence, and — on the TABLE path, where the heading is
        // conditional — no sub-heading over the silence either.
        //
        // A SECOND STAGE FOR THE SECOND HALF, AND NOT AN OVERSIGHT. DCH_STANDARD draws
        // SKETCH_DEVELOPMENT as CARDS, and `_render_cards` heads EVERY row unconditionally on both
        // surfaces — so a card's heading proves nothing either way. It is `_render_table`'s per-row
        // block that asks `has_extra` first, and FOLLOW_UP is the stage this file already uses to
        // exercise it.
        val notTaken = build(
            schemaOf(
                StageDto(
                    number = 22, key = "FOLLOW_UP", title = "Follow up",
                    entities = listOf(
                        EntityDto(
                            key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                            labelField = "product",
                            fields = listOf(
                                textField("product", "Product", role = "TABLE_COLUMN"),
                                imageField("shelfPhoto", "Shop shelf", required = true),
                            ),
                        )
                    )
                )
            ),
            draftOf(
                "FOLLOW_UP",
                rows = listOf(
                    DraftRow(
                        id = "followUp#1",
                        values = mapOf("product" to JsonPrimitive("Bandha runner")),
                    ),
                ),
            ),
            imageFor = everyImageResolves,
        )
        val printedMissing = printedText(notTaken)
        assertTrue(
            "this half only means something on the table path: " + shape(notTaken),
            notTaken.blocks.any { it is TableBlock },
        )
        assertFalse(
            "the office prints no such note for a GALLERY field:\n" + printedMissing,
            printedMissing.contains("Shop shelf: Not recorded."),
        )
        assertFalse(
            "a sub-heading was drawn over a row with nothing left to print: " + headings(notTaken),
            headings(notTaken).any { it.contains("Bandha runner") },
        )
    }

    /**
     * A DOCUMENT IS NOT OFFERED TO THE IMAGE RESOLVER AS THOUGH IT WERE A PHOTOGRAPH.
     *
     * `_images` filters on IMAGE and IMAGE_LIST; `imagesOf` asked `isMedia`, so a FILE's media id was
     * handed to `imageFor` and - in the app, where that resolver reads the downloaded media cache
     * rather than returning null - a PDF would have been drawn as a plate with the field's label
     * under it. Nothing in the file would have said the plate was a sanction order.
     */
    @Test
    fun `an attached document is never placed as a plate`() {
        val schema = schemaOf(
            StageDto(
                number = 1, key = "WORKSHOP_SETUP", title = "Setup",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Setup",
                        fields = listOf(attachmentField("sanctionOrder", "Sanction order", "FILE")),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf("WORKSHOP_SETUP", values = mapOf("sanctionOrder" to JsonPrimitive("media-file-1"))),
            imageFor = everyImageResolves,
        )
        assertTrue(
            "a document was placed as a photograph: " + shape(document),
            document.blocks.none { it is ImageBlock || it is ImageGridBlock },
        )
        assertTrue(printedText(document).contains("Sanction order: 1 document attached"))
    }

    // -- 7. A borrowed photograph is captioned from the frozen copy ------------------------------

    /**
     * `ReportBuilder._reference_caption` - THE ONE PLACE A SUBMITTED REPORT RE-RESOLVED A LIVE NAME.
     *
     * `imagesOf`'s reference pass captioned the borrowed plate with `DwRefLabels.label(refId)`, which
     * resolves the referenced record AS IT STANDS TODAY. Hydration copied that record's name onto
     * THIS row at save time and every other line on the page prints the frozen copy, so a record
     * renamed after the workshop closed put both answers on one page: the sub-section reading
     * "Sketch: SK-04" out of the frozen box, and directly beneath it the borrowed plate captioned
     * "SK-04 - revised 2027".
     *
     * The mapping is asked and not guessed: `field_to_dict` publishes `REFERENCE_HYDRATION` per field
     * as `refHydration`, which already says which box the name landed in.
     */
    @Test
    fun `a borrowed photograph is captioned with the row's frozen name, not the live record`() {
        val schema = schemaOf(
            StageDto(
                number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development",
                entities = listOf(
                    EntityDto(
                        key = "sketch", cardinality = "COLLECTION", title = "Sketch",
                        labelField = "code",
                        fields = listOf(textField("code", "Sketch code"), imageField("sketchImage", "Sketch")),
                    )
                )
            ),
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", cardinality = "COLLECTION", title = "Prototype",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Code"),
                            // The box hydration copied the sketch's name into, at save time.
                            textField("sketchName", "Sketch"),
                            refField("sketchRef", "Sketch", "DwSketch", hydration = mapOf("name" to "sketchName")),
                        ),
                    )
                )
            ),
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "SKETCH_DEVELOPMENT" to StageDraft(
                    stageId = "SKETCH_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "sketch#kzzq1p8n4m2wd7xr",
                            values = mapOf(
                                // RENAMED AFTER THE WORKSHOP CLOSED, which is the whole case.
                                "code" to JsonPrimitive("SK-04 revised 2027"),
                                "sketchImage" to JsonPrimitive("media-sketch-4"),
                            ),
                        )
                    ),
                ),
                "PROTOTYPE_DEVELOPMENT" to StageDraft(
                    stageId = "PROTOTYPE_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "prototype#row-1",
                            values = mapOf(
                                "code" to JsonPrimitive("PT-01"),
                                "sketchName" to JsonPrimitive("SK-04"),
                                "sketchRef" to JsonPrimitive("kzzq1p8n4m2wd7xr"),
                            ),
                        )
                    ),
                ),
            ),
        )

        val borrowed = build(schema, draft, imageFor = everyImageResolves).blocks
            .filterIsInstance<ImageBlock>()
            .filter { it.image.source.endsWith("media-sketch-4") }
        assertTrue("the borrowed plate is missing", borrowed.isNotEmpty())
        assertTrue(
            "the caption re-resolved the live record: " + borrowed.map { it.caption },
            borrowed.any { it.caption == "SK-04" },
        )
        assertTrue(
            "no plate may carry the renamed live label: " + borrowed.map { it.caption },
            borrowed.none { it.caption == "SK-04 revised 2027" },
        )
    }

    // -- 8. Only a stage singleton gets a metric row ---------------------------------------------

    /**
     * `_render_stage` IS THE ONLY RENDERER ON THE SERVER THAT BUILDS A `MetricRowBlock`.
     *
     * Neither `_render_cards` nor `_render_narrative` nor `_render_table`'s per-row block emits one,
     * and `_render_narrative`'s pair roles are KEY_VALUE, COVER_FIELD and TABLE_COLUMN - METRIC is in
     * none of them. So a METRIC field on a COLLECTION prints nowhere at the office, while
     * `renderEntity` drew a band of big numbers for every row. Latent today (all four METRIC fields
     * in the shipped registry are on singletons) and a structural divergence that would appear with
     * no code change the day one is declared on a collection: different block heights, different page
     * boundaries, one workshop, two files.
     */
    @Test
    fun `a collection row gets no metric row, and a stage singleton still does`() {
        val schema = schemaOf(
            StageDto(
                number = 17, key = "COST_INTEGRITY", title = "Costing",
                entities = listOf(
                    EntityDto(
                        key = "costing", cardinality = "SINGLETON", title = "Costing",
                        fields = listOf(metricField("sheets", "Cost sheets")),
                    ),
                    EntityDto(
                        key = "costSheet", cardinality = "COLLECTION", title = "Cost sheet",
                        labelField = "name",
                        fields = listOf(textField("name", "Product"), metricField("units", "Units")),
                    ),
                )
            )
        )
        val singletonOnly = build(
            schema,
            draftOf("COST_INTEGRITY", values = mapOf("sheets" to JsonPrimitive(4))),
        )
        assertTrue(
            "the stage singleton must still get its metric row: " + shape(singletonOnly),
            singletonOnly.blocks.any { it is MetricRowBlock },
        )

        val rowsOnly = build(
            schema,
            draftOf(
                "COST_INTEGRITY",
                rows = listOf(
                    DraftRow(
                        id = "costSheet#row-1",
                        values = mapOf("name" to JsonPrimitive("Bandha runner"), "units" to JsonPrimitive(12)),
                    )
                ),
            ),
        )
        assertTrue(
            "a collection row must not grow a metric row: " + shape(rowsOnly),
            rowsOnly.blocks.none { it is MetricRowBlock },
        )
    }

    // -- The whole point, said once --------------------------------------------------------------

    @Test
    fun `no answer is lost by any of the five changes`() {
        // The five fixes above all MOVE or ADD content; none may remove any. This is the backstop:
        // one record carrying one field of every role, and every value must reach the file.
        val schema = schemaOf(
            StageDto(
                number = 6, key = "EXISTING_PRODUCTS_BASELINE", title = "Baseline",
                entities = listOf(
                    EntityDto(
                        key = "baseline", cardinality = "SINGLETON", title = "Baseline",
                        fields = listOf(
                            richField("background", "Background", "NARRATIVE"),
                            richField("gaps", "Gaps", "BULLETS"),
                            metricField("looms", "Looms"),
                            textField("village", "Village"),
                            textField("block", "Block", role = "COVER_FIELD"),
                            textField("district", "District", role = "TABLE_COLUMN"),
                            textField("unknownRole", "Something new", role = "SOMETHING_THE_SERVER_ADDED"),
                            textField("hidden", "Internal", role = "HIDDEN"),
                        ),
                    )
                )
            )
        )
        val printed = printedText(
            build(
                schema,
                draftOf(
                    "EXISTING_PRODUCTS_BASELINE",
                    values = mapOf(
                        "background" to rich("PARAGRAPH" to "Fourteen households."),
                        "gaps" to rich("BULLET_ITEM" to "No dye vat"),
                        "looms" to JsonPrimitive(14),
                        "village" to JsonPrimitive("Barpali"),
                        "block" to JsonPrimitive("Bargarh"),
                        "district" to JsonPrimitive("Bargarh district"),
                        "unknownRole" to JsonPrimitive("a role this build has never heard of"),
                        "hidden" to JsonPrimitive("never printed"),
                    ),
                ),
            )
        )

        listOf(
            "Fourteen households.", "No dye vat", "Barpali", "Bargarh", "Bargarh district", "Looms: 14",
            // AN UNKNOWN ROLE STILL PRINTS. The `when` this rewrite replaced had an `else ->` arm
            // that caught a blank or unrecognised role and put it in the grid; naming the four
            // pair-roles positively would have turned that into a silent drop the day the server
            // added a fifth — the same shape as the RICH_TEXT drop this file was rewritten over.
            "a role this build has never heard of",
        ).forEach { expected ->
            assertTrue("'$expected' did not reach the file:\n$printed", printed.contains(expected))
        }
        assertFalse("a HIDDEN field must never print:\n$printed", printed.contains("never printed"))
    }
}
