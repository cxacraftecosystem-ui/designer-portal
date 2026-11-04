package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.entity
import com.designprototype.workshop.data.liveFields
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwSketchDerivationPhotoTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists(),
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private fun entityOf(stageKey: String, entityKey: String): EntityDto =
        schema.stages.firstOrNull { it.key == stageKey }?.entity(entityKey)
            ?: throw AssertionError("the registry declares no `$stageKey`.`$entityKey`")

    private fun siblingsOf(stageKey: String, entityKey: String): Map<String, FieldDto> =
        entityOf(stageKey, entityKey).liveFields.associateBy { it.key }

    private fun portalSource(): String {
        val file = File("../../frontend/components/sketches/upload/SharedPhotoField.tsx")
        assertTrue(
            "expected the portal's shared-photograph card at ${file.absolutePath}. This test is the " +
                "only mechanical check that the two clients call this card the same thing and " +
                "promise the same thing; if the tree moved, fix the path rather than deleting the " +
                "assertion.",
            file.exists(),
        )
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `the shared photograph card carries the portal's label`() {
        assertTrue(
            "the portal no longer labels its shared card “$DW_SHARED_PHOTOGRAPH_LABEL”. Either it " +
                "was renamed there and this client must follow, or this constant drifted.",
            portalSource().contains("label=\"$DW_SHARED_PHOTOGRAPH_LABEL\""),
        )
    }

    @Test
    fun `the idle sentence is the portal's, character for character`() {
        assertTrue(
            "the portal's “choose it once” sentence has changed. It is carried verbatim on this " +
                "client; re-copy it rather than paraphrasing, or say here why the two must differ.",
            portalSource().contains(DW_SHARED_PHOTOGRAPH_IDLE),
        )
    }

    @Test
    fun `the chosen sentence tells this client's truth and not the portal's`() {
        val sentence = dwSharedPhotographSentence(imageLabel = "Sketch image", rowName = "Untitled 1")

        assertTrue(
            "the shared clause must promise EVERY panel below, not a count of them",
            sentence.startsWith("Every panel below works from this photograph."),
        )
        assertFalse(
            "“Nothing has been filed yet” is FALSE on this client: the photograph was imported into " +
                "filesDir and written into the row before any panel could see it. A designer who " +
                "believed this would go looking for a save button that has already been pressed.",
            sentence.contains("Nothing has been filed yet"),
        )
        assertTrue(
            "the sentence must name the field the photograph is attached to",
            sentence.contains("“Sketch image”"),
        )
        assertTrue(
            "and the row, so a designer can check the claim against the picker above",
            sentence.contains("“Untitled 1”"),
        )
        assertTrue(
            "it must still say what has NOT happened yet, which is the half that stops “I uploaded " +
                "it” meaning two different things to the designer and to the repository",
            sentence.contains("nothing further is written until a button", ignoreCase = true),
        )
    }

    @Test
    fun `the three derivation cards carry the portal's own names`() {
        for ((constant, portal) in listOf(
            DW_MEASURE_CARD_TITLE to "sketches/upload/MeasureFromPhotoCard.tsx",
            DW_TRACE_CARD_TITLE to "sketches/upload/SketchTraceField.tsx",
            DW_RECTIFY_CARD_TITLE to "designworkshop/SketchRectifyField.tsx",
        )) {
            val file = File("../../frontend/components/$portal")
            assertTrue(
                "expected the portal's panel at ${file.absolutePath}. If the tree moved, fix the " +
                    "path rather than deleting the assertion — this is the only mechanical check " +
                    "that the two clients name these cards the same thing.",
                file.exists(),
            )
            assertTrue(
                "$portal no longer calls its card “$constant”. Either it was renamed there and this " +
                    "client must follow, or this constant drifted. Divergence is available where a " +
                    "clause would be FALSE on one client — a NAME never is.",
                file.readText(Charsets.UTF_8).contains(constant),
            )
        }
    }

    @Test
    fun `the chosen sentence survives a row with no name`() {
        val sentence = dwSharedPhotographSentence(imageLabel = "Sketch image", rowName = null)
        assertTrue(sentence.contains("“Sketch image” on this record"))
        assertFalse("an empty pair of quotes is a row nobody can identify", sentence.contains("“”"))
    }

    @Test
    fun `a plate field counts as the two cards it mounts`() {
        assertEquals(2, dwDerivationCardCount(offersPlate = true, offersMeasure = false))
        assertEquals(3, dwDerivationCardCount(offersPlate = true, offersMeasure = true))
        assertEquals(1, dwDerivationCardCount(offersPlate = false, offersMeasure = true))
        assertEquals(0, dwDerivationCardCount(offersPlate = false, offersMeasure = false))
    }

    @Test
    fun `one card is not a shared photograph`() {
        assertTrue(dwSharesOnePhotograph(offersPlate = true, offersMeasure = true))
        assertTrue(
            "a plate field alone still mounts two cards, and they must not choose separately",
            dwSharesOnePhotograph(offersPlate = true, offersMeasure = false),
        )
        assertFalse(
            "a measuring card on its own has its own chooser over the same list; a second one above " +
                "it is the duplication, not the fix",
            dwSharesOnePhotograph(offersPlate = false, offersMeasure = true),
        )
        assertFalse(dwSharesOnePhotograph(offersPlate = false, offersMeasure = false))
    }

    @Test
    fun `the registry gives a sketch a shared photograph and a prototype none`() {
        for ((location, expected) in mapOf(
            ("SKETCH_DEVELOPMENT" to "sketch") to true,
            ("PROTOTYPE_DEVELOPMENT" to "prototype") to false,
        )) {
            val (stageKey, entityKey) = location
            val siblings = siblingsOf(stageKey, entityKey)
            val offersPlate = siblings.values.any { dwOffersSketchRectify(it, siblings) }
            val offersMeasure = siblings.values.any { dwOffersPhotoMeasure(it, siblings) }
            assertEquals(
                "`$stageKey`.`$entityKey` changed which derivation cards it offers, which changes " +
                    "whether the shared photograph card belongs above them",
                expected,
                dwSharesOnePhotograph(offersPlate, offersMeasure),
            )
            assertTrue(
                "both halves of the Upload tab must still offer the measuring card — it is the one " +
                    "derivation a prototype has at all",
                offersMeasure,
            )
        }
    }

    @Test
    fun `the shared card and the hosted supply are decided together`() {
        val code = sourceOf("DwSketchDerivationPhoto.kt")

        assertTrue(
            "the section must ask dwSharesOnePhotograph rather than testing plateField again",
            code.contains("val shares = dwSharesOnePhotograph("),
        )
        assertTrue(
            "the card must be drawn only under that gate",
            code.contains("if (shares) {"),
        )
        assertTrue(
            "and the supply must come off the same value, so the two cannot disagree",
            code.contains("val supply = if (shares) {"),
        )
        assertFalse(
            "no panel may be handed a freshly constructed Hosted(...) — that is a second reading of " +
                "the decision, and it is how a prototype came to be shown a sketch's card",
            code.contains("supply = DwSketchPhotographSupply.Hosted("),
        )
    }

    @Test
    fun `a record with no photograph is told so rather than shown nothing`() {
        assertTrue(
            "the section must draw the empty sentence itself — the shared card is not always " +
                "composed, and a prototype needs this state said too",
            sourceOf("DwSketchDerivationPhoto.kt").contains("if (sources.isEmpty()) {"),
        )
        assertTrue(
            "the chooser must stop gating the section on there being photographs; that gate is what " +
                "made an empty row render nothing at all",
            !sourceOf("DwSketchChooserUpload.kt").contains("derivationSources.isNotEmpty()"),
        )
        val prototype = dwNoPhotographSentence(
            offersPlate = false,
            offersMeasure = true,
            photoFieldLabels = listOf("Prototype photographs", "360° capture"),
        )
        assertTrue(
            "the empty sentence must name the stage form as well as this tab: a prototype's " +
                "`prototypePhotos` has no capture card here, and pointing only at controls above " +
                "sends a designer looking for one that is elsewhere",
            prototype.contains("stage form"),
        )
        assertTrue(
            "and it must still say that nothing needs uploading twice, which is the whole report",
            prototype.contains("nothing needs uploading twice"),
        )
    }

    @Test
    fun `the empty sentence names only the acts this record offers`() {
        val sketch = dwNoPhotographSentence(
            offersPlate = true,
            offersMeasure = true,
            photoFieldLabels = listOf("Sketch image"),
        )
        assertTrue(
            "a sketch offers all three cards, so all three acts are named: $sketch",
            sketch.contains("trace, straighten or measure against"),
        )

        val prototype = dwNoPhotographSentence(
            offersPlate = false,
            offersMeasure = true,
            photoFieldLabels = listOf("Prototype photographs", "360° capture"),
        )
        assertFalse(
            "a prototype has no tracing panel — `dwOffersSketchRectify` refuses “Measurement sheet” " +
                "and “3D model” — so the word must not appear: $prototype",
            prototype.contains("trace"),
        )
        assertFalse(
            "and no straightening panel, for the same one reason: $prototype",
            prototype.contains("straighten"),
        )
        assertTrue(
            "what a prototype does offer is measuring, and that is what it must say: $prototype",
            prototype.contains("nothing to measure against"),
        )

        val plateOnly = dwNoPhotographSentence(
            offersPlate = true,
            offersMeasure = false,
            photoFieldLabels = emptyList(),
        )
        assertFalse(
            "a record with a plate field and no dimension has no measuring card, and the shared " +
                "photograph card IS drawn over its two panels — `dwSharesOnePhotograph(true, false)` " +
                "is already two. That is the arrangement a sentence hardcoded to all three would get " +
                "wrong where nobody is looking: $plateOnly",
            plateOnly.contains("measure"),
        )
        assertTrue(plateOnly.contains("nothing to trace or straighten"))
    }

    @Test
    fun `the empty sentence names the image fields where it knows them`() {
        val named = dwNoPhotographSentence(
            offersPlate = false,
            offersMeasure = true,
            photoFieldLabels = listOf("Prototype photographs", "360° capture"),
        )
        assertTrue(named.contains("“Prototype photographs” or “360° capture”"))

        val unnamed = dwNoPhotographSentence(
            offersPlate = true,
            offersMeasure = true,
            photoFieldLabels = emptyList(),
        )
        assertTrue(
            "with no labels the sentence still points somewhere: $unnamed",
            unnamed.contains("one of this record's image fields"),
        )
        assertFalse(
            "and it invents no quoted field name it never read: $unnamed",
            unnamed.contains("“"),
        )
    }

    @Test
    fun `the size line says it is still opening rather than inventing a frame`() {
        val line = dwPhotographSizeSentence(sourceWidth = 0, sourceHeight = 0, sizeBytes = 2_400_000L)
        assertFalse("a zero frame is not a frame", line.contains("0×0"))
        assertTrue(line.contains("Opening the photograph…"))
        assertTrue("the file size is known before the frame is, and is worth saying", line.contains("2.4 MB"))
    }

    @Test
    fun `the size line names the frame and the file`() {
        assertEquals(
            "4032×3024 · 2.4 MB.",
            dwPhotographSizeSentence(sourceWidth = 4032, sourceHeight = 3024, sizeBytes = 2_400_000L),
        )
        assertEquals(
            "4032×3024.",
            dwPhotographSizeSentence(sourceWidth = 4032, sourceHeight = 3024, sizeBytes = 0L),
        )
    }

    @Test
    fun `a tracing card nobody has used has no summary`() {
        assertNull(
            dwTraceCardSummary(
                tracedName = "",
                shapeCount = 0,
                nodeCount = 0,
                wasPreview = true,
                attachedName = "",
            )
        )
    }

    @Test
    fun `a finished trace is reported with its photograph and its counts`() {
        val summary = dwTraceCardSummary(
            tracedName = "sheet-3.jpg",
            shapeCount = 412,
            nodeCount = 5310,
            wasPreview = false,
            attachedName = "",
        )
        assertEquals("traced from “sheet-3.jpg” · 412 paths · 5310 nodes", summary)
    }

    @Test
    fun `a preview is reported as a preview`() {
        val summary = dwTraceCardSummary(
            tracedName = "sheet-3.jpg",
            shapeCount = 88,
            nodeCount = 940,
            wasPreview = true,
            attachedName = "",
        )
        assertTrue(summary.orEmpty().startsWith("a preview traced from “sheet-3.jpg”"))
    }

    @Test
    fun `an attachment is reported even after the drawing is forgotten`() {
        val summary = dwTraceCardSummary(
            tracedName = "",
            shapeCount = 0,
            nodeCount = 0,
            wasPreview = true,
            attachedName = "Line art / vector file",
        )
        assertEquals("attached as “Line art / vector file”", summary)
    }

    private fun sourceOf(name: String): String {
        val file = File("src/main/java/com/designprototype/workshop/ui/designworkshop/$name")
        assertTrue("$name is missing — a rename must move this guard", file.exists())
        return file.readText(Charsets.UTF_8)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }

    @Test
    fun `the shared photograph section is actually mounted`() {
        val chooser = sourceOf("DwSketchChooserUpload.kt")
        assertTrue(
            "DwSketchDerivationSection is composed nowhere. A section nobody can reach is the same " +
                "as a section that does not exist, and this one is the whole of requirement 5 on " +
                "this client.",
            chooser.contains("DwSketchDerivationSection("),
        )
        assertTrue(
            "the section must be gated on `half.reconciled` like every other write on this tab — " +
                "writing a row back over a collection this device has not read is how a stage loses " +
                "rows nobody deleted",
            chooser.contains("enabled = half.reconciled"),
        )
        assertTrue(
            "the measuring card's proposal needs a door of its own on this tab; without one the " +
                "card is mounted and cannot write",
            chooser.contains("onWriteScalar(half, chosenRow, key, value)"),
        )
    }

    @Test
    fun `a superseded trace cannot write its result`() {
        val panel = sourceOf("DwSketchTracePanel.kt")

        assertTrue(
            "the run must carry the token it started under",
            panel.contains("val token = runToken"),
        )
        assertTrue(
            "and must check it after the trace returns, before anything visible is written",
            panel.contains("if (token != runToken) return@launch"),
        )
        assertTrue(
            "a change of photograph must move the token BEFORE it cancels, or a run in the window " +
                "between its last suspension point and its first assignment writes anyway",
            panel.contains("runToken++"),
        )
        assertTrue(
            "and must cancelAndJoin rather than cancel, so the old run's finally — which owns the " +
                "spinner — has really run before a new one starts",
            panel.contains("job?.cancelAndJoin()"),
        )

        val reset = panel.substringAfter("fun forgetDerivations()").substringBefore("}")
        for (dropped in listOf("result = null", "resultWire = null", "difference = null", "frame = null")) {
            assertTrue(
                "forgetDerivations must drop `$dropped`; a reset written out twice with one line " +
                    "missing from the second copy is how this comes to be incomplete",
                reset.contains(dropped),
            )
        }
    }

    @Test
    fun `the derivation panels open no file dialogs`() {
        for (name in listOf(
            "DwSketchTracePanel.kt",
            "DwSketchRectifyField.kt",
            "DwSketchDerivationPhoto.kt",
        )) {
            val code = sourceOf(name)
            assertFalse(
                "a launcher has appeared in $name. Photographs reach these panels by being imported " +
                    "first; a picker here would be a second import route.",
                code.contains("rememberLauncherForActivityResult") ||
                    code.contains("ActivityResultContracts"),
            )
        }
    }

    @Test
    fun `the shared preview decodes small`() {
        val code = sourceOf("DwSketchDerivationPhoto.kt")
        assertTrue(
            "the preview must pass its own ceiling to decodeForDisplay. Dropping the argument takes " +
                "the default — 2400px, about 6 MB — and holds it for the life of the section.",
            code.contains("DwImageDecode.decodeForDisplay(path, DW_SHARED_PREVIEW_EDGE_PX)"),
        )
        assertTrue(
            "the ceiling must stay well under the marking copy's, or the preview stops being a " +
                "preview and starts being a third working copy",
            DW_SHARED_PREVIEW_EDGE_PX <= 512,
        )
        assertFalse(
            "nothing in this file may recycle a bitmap. Compose holds one through an ImageBitmap for " +
                "as long as a frame is on screen, and the only recycler in this feature is the trace " +
                "runtime, on a bitmap no composable has ever seen.",
            code.contains(".recycle()"),
        )
    }
}
