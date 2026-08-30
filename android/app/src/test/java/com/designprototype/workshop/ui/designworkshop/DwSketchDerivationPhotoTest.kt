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

/**
 * ONE PHOTOGRAPH FEEDS EVERY DERIVATION CARD, AND THE TWO CLIENTS AGREE ABOUT WHAT THAT IS CALLED —
 * requirements 5, 6, 18 and 20 on the handset.
 *
 * ── WHAT THIS PINS AND WHY EACH HALF NEEDS PINNING ────────────────────────────────────────────
 *
 * **The words**, because requirement 20 is not a feeling. This repository already holds the five
 * export-format strings identical across the two clients and treats that as the standard, and
 * `DwSketchTracePlateMathTest` already reads the portal's own source rather than a transcription of
 * it — *"a transcription of the portal's string is just a third copy that can go stale in its own
 * right"*. The shared-photograph card is the newest surface both clients have, so the same discipline
 * applies to it from the day it lands rather than after the first divergence.
 *
 * **The one sentence that deliberately DOES NOT match**, which is the more important half. The other
 * client tells a designer "Nothing has been filed yet", and that is true there — its pick is an
 * unfiled `File` in memory. It is false here: a photograph a panel can see on this client is one
 * `DwMediaBridge.attach` has already imported into `filesDir` and written into the row. Copying that
 * sentence across would send a designer looking for a save button that has already been pressed, so
 * the divergence is asserted rather than left to be "fixed" by somebody tidying the two clients into
 * agreement.
 *
 * **The collapsed summaries**, because a collapsed card that says only its own title is
 * indistinguishable from one nobody has touched — which is the substance of the report the accordions
 * answer, and it was true of the tracing card until this change.
 *
 * ── AND THE SOURCE GUARDS AT THE END ──────────────────────────────────────────────────────────
 *
 * There is no Robolectric and no `createComposeRule` on this module's test classpath — see the block
 * comment in `DwPhotoMeasureFieldTest` — so the composition-level properties are read off the source.
 * The point is not to make a regression impossible but to make it DELIBERATE, past a test that names
 * the failure it came from.
 */
class DwSketchDerivationPhotoTest {

    /* ────────────────────────────────────────────────────────────────────────────────────────────
     * The bundled registry
     *
     * THE SHIPPED ASSET AND NOT THE LIVE SERVER, for `DwPhotoMeasureFieldTest`'s reason:
     * `design-workshop-schema.json` is what a handset renders from before it has ever had a
     * connection, so it is the copy that decides what a courtyard sees.
     * ──────────────────────────────────────────────────────────────────────────────────────────── */

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
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

    /* ────────────────────────────────────────────────────────────────────────────────────────────
     * The words, against the other client's own source
     * ──────────────────────────────────────────────────────────────────────────────────────────── */

    /** The unit tests run with `app/` as the working directory — see `DwSketchTracePlateMathTest`. */
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

    /**
     * **THE CARD IS CALLED THE SAME THING ON BOTH CLIENTS.**
     *
     * A card that does one job under two names is the same defect as a format list that does, and the
     * format list is the precedent this repository already holds itself to.
     */
    @Test
    fun `the shared photograph card carries the portal's label`() {
        assertTrue(
            "the portal no longer labels its shared card “$DW_SHARED_PHOTOGRAPH_LABEL”. Either it " +
                "was renamed there and this client must follow, or this constant drifted.",
            portalSource().contains("label=\"$DW_SHARED_PHOTOGRAPH_LABEL\""),
        )
    }

    /**
     * **THE IDLE SENTENCE IS THE PORTAL'S, WORD FOR WORD.**
     *
     * Every clause of it is true on this client too — both panels below do work from one photograph,
     * and neither files anything until a button in it is pressed — so there is no reason for two
     * versions of it to exist, and every reason for the two not to drift.
     */
    @Test
    fun `the idle sentence is the portal's, character for character`() {
        assertTrue(
            "the portal's “choose it once” sentence has changed. It is carried verbatim on this " +
                "client; re-copy it rather than paraphrasing, or say here why the two must differ.",
            portalSource().contains(DW_SHARED_PHOTOGRAPH_IDLE),
        )
    }

    /**
     * **AND THE ONE SENTENCE THAT MUST NOT BE COPIED.**
     *
     * The portal's chosen-state sentence says "Nothing has been filed yet". That is a promise about an
     * unfiled browser `File`. On this client the photograph is already imported into `filesDir` and
     * already written into the row — a panel cannot be handed a path to anything else — so the same
     * words here would be the receipt-that-understates version of a failure this tab has already paid
     * for once in the other direction.
     */
    @Test
    fun `the chosen sentence tells this client's truth and not the portal's`() {
        val sentence = dwSharedPhotographSentence(imageLabel = "Sketch image", rowName = "Untitled 1")

        assertTrue(
            // "EVERY" AND NOT THE PORTAL'S "BOTH", WHICH IS A COUNT AND NOT A PHRASING. The portal
            // mounts two panels under this card; this client mounts three, because
            // `dwOffersSketchRectify` gives it a straightening panel the browser's Upload tab has no
            // equivalent of. A word that counted to two under three cards would leave a designer
            // working out which two — on the one sentence whose whole job is to say that they all
            // follow one photograph. Nothing is enumerated instead: see the function's own header for
            // why a list written there would be a second description of the mount.
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

    /**
     * **EVERY DERIVATION CARD IS CALLED THE SAME THING ON BOTH CLIENTS — requirement 20.**
     *
     * ── THE TIE-BREAK THIS TEST IS THE ENFORCEMENT OF ─────────────────────────────────────────
     *
     * All three of these cards had TWO names each until the headers became accordion controls: one
     * spelling while shut and another while open. Picking one of the two was necessary — a control
     * whose label changes when you press it reads as a different control — but the tie-break used
     * was internal to this client, and two of the three landed on the spelling the portal does not
     * use. So a designer who traced a sheet in a browser and then picked up the phone was looking for
     * "Trace a sketch into line art" under a card called something else.
     *
     * The measuring card was never wrong, and it is the precedent: `MeasureFromPhotoCard.tsx:164`
     * carries this repository's own re-check instruction in its header —
     * `grep -rn "Measure a dimension from a photograph" frontend/ android/` — which only means
     * anything if the answer is meant to be both trees. This is that grep, run by CI.
     *
     * READ OFF THE PORTAL'S SOURCE, NOT A TRANSCRIPTION OF IT, for `DwSketchTracePlateMathTest`'s
     * reason: a transcription is a third copy that can go stale in its own right. A rename on either
     * client fails here, which is the point — it is meant to be a decision, not a drift.
     */
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

    /** A caller with no name for the row still gets a sentence, and it never prints an empty quote. */
    @Test
    fun `the chosen sentence survives a row with no name`() {
        val sentence = dwSharedPhotographSentence(imageLabel = "Sketch image", rowName = null)
        assertTrue(sentence.contains("“Sketch image” on this record"))
        assertFalse("an empty pair of quotes is a row nobody can identify", sentence.contains("“”"))
    }

    /* ────────────────────────────────────────────────────────────────────────────────────────────
     * WHERE THE SHARED CARD IS DRAWN AND WHERE IT IS NOT — requirement 7's "where applicable"
     *
     * The rest of this class is about the words. This section is about the one place a word would be
     * spent on a screen that should not have it at all: a card called "Photograph of the sketch",
     * printing sentences that name a tracing panel, over a prototype that has neither. It was drawn
     * there for one commit, because the section is mounted from a composable that runs twice — once
     * per half of the Upload tab — and nothing had asked whether the halves wanted the same thing.
     * ──────────────────────────────────────────────────────────────────────────────────────────── */

    /**
     * **A PLATE FIELD IS TWO CARDS, NOT ONE, AND THE ARITHMETIC HAS TO KNOW IT.**
     *
     * [DwSketchDerivationSection] draws `DwSketchRectifyPanel` AND `DwSketchTracePanel` off one FILE
     * field — `dwOffersSketchTrace` is a one-line delegation to `dwOffersSketchRectify` so the two can
     * never be offered in different places. Counting FIELDS instead of CARDS would make a record with
     * a plate field and no dimensions come out at one, drop the shared card, and leave a straightening
     * panel and a tracing panel working from two independent chip selections — which is the exact
     * failure the shared card exists to prevent, arrived at by tidying.
     */
    @Test
    fun `a plate field counts as the two cards it mounts`() {
        assertEquals(2, dwDerivationCardCount(offersPlate = true, offersMeasure = false))
        assertEquals(3, dwDerivationCardCount(offersPlate = true, offersMeasure = true))
        assertEquals(1, dwDerivationCardCount(offersPlate = false, offersMeasure = true))
        assertEquals(0, dwDerivationCardCount(offersPlate = false, offersMeasure = false))
    }

    /**
     * **A PHOTOGRAPH IS SHARED WHEN MORE THAN ONE CARD IS LOOKING AT IT, AND OTHERWISE IT IS JUST A
     * PHOTOGRAPH.**
     *
     * The one-card case is the one that matters and it is a prototype: there the measuring card
     * already owns a chooser over exactly the photographs a shared card would be choosing from, so
     * the shared card adds a second control and no information — two pickers for one photograph,
     * which is the literal duplication the whole change was reported to remove.
     */
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

    /**
     * **AND AGAINST THE BUNDLED REGISTRY, WHICH IS WHAT A COURTYARD ACTUALLY RENDERS FROM.**
     *
     * The arithmetic above is only as good as the two predicates feeding it, and those are declaration
     * lookups that fail silently — a renamed key or a retyped field takes a card away with no warning
     * and no log. So the two entities the Upload tab actually has halves for are asserted by name.
     *
     * A SKETCH SHARES: `lineArtFile` is "Line art / vector file", which `DW_LINE_ART_KEY` matches, so
     * a sketch gets the straightening panel, the tracing panel and the measuring card — three cards,
     * one photograph, and the card above them is the only thing that says which.
     *
     * A PROTOTYPE DOES NOT: its two FILE fields are `measurementSheet` and `modelFile`, and neither
     * "Measurement sheet" nor "3D model" is the home of a plate. One card, so no shared card — which
     * is also what `UploadTabPanel.tsx:391-407` decided on the other client, and requirement 20 is
     * about the two of them not disagreeing on a question a designer can see the answer to.
     */
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

    /**
     * **THE CARD AND THE SUPPLY ARE ONE DECISION, READ ONCE.**
     *
     * The pairing is the load-bearing half and it can only be checked here. A shared card drawn
     * WITHOUT a hosted supply is a chooser the panels below ignore; a hosted supply WITHOUT the card
     * points three panels at a photograph nothing on the screen names, and `DwSketchTracePanel`'s "No
     * photograph has been chosen above yet" would then be describing a control that does not exist.
     * One `val`, read at four sites, is what makes those two states unrepresentable.
     */
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

    /**
     * **A RECORD WITH NO PHOTOGRAPH SAYS SO. A SECTION THAT VANISHES IS A FEATURE NOBODY HAS.**
     *
     * Every card in the section returns early with no photographs, which is right on a stage form —
     * the field they sit on is on screen with its own capture card and its emptiness is self-evident
     * — and wrong on the Upload tab, where these cards are the only sign the capability exists. The
     * other client fixed the same omission and named what it had cost: *"A control that vanishes is
     * indistinguishable from a build that does not have the feature, which is precisely how this
     * surface came to be reported as 'completely missing'."*
     *
     * The sentence must not point at a control that is not there, which is the second half of the
     * lesson (`MeasureFromPhotoCard.tsx:604-611`): a prototype's `prototypePhotos` has no capture
     * card on this tab, so "attach one above" alone would send a designer looking for a picker that
     * is on the stage form. Both destinations are named and neither is claimed to be here.
     */
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

    /**
     * **A PROTOTYPE IS NOT TOLD ABOUT PANELS IT DOES NOT HAVE — requirement 7.**
     *
     * This is the assertion the constant this function replaced could not make. It read "…so there is
     * nothing to trace, straighten or measure against" on every record that reached it, and it reaches
     * prototypes: `dwOffersSketchRectify` refuses both FILE fields a prototype declares — "Measurement
     * sheet" and "3D model" are not the home of a plate — so that half of the Upload tab mounts no
     * tracing panel and no straightening panel at all.
     *
     * A designer on an empty prototype row was therefore reading, in the ONE sentence on that screen,
     * the names of two capabilities the record does not have. A control that is missing can be looked
     * for and not found; a capability that was never offered, named in the only sentence there is,
     * reads as a build that is broken — and there is nowhere to scroll to find out otherwise.
     */
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

    /**
     * **AND IT NAMES THE FIELDS A PHOTOGRAPH WOULD GO IN, WHICH IS THE OTHER CLIENT'S OWN FIX.**
     *
     * `MeasureFromPhotoCard.tsx` corrected exactly this sentence for exactly this half and recorded
     * why: a prototype's photographs live in `prototypePhotos` AND `turntablePhotos`, and only the
     * second has a capture card on this tab, so an un-named sentence sends a designer hunting for a
     * picker that is on the stage form. Both fields are named, and so are both places to attach one.
     *
     * THE EMPTY LIST IS A STATE AND NOT A BUG: a caller that does not know which fields these are must
     * still get a sentence that points somewhere, and must never get one naming a field nobody read.
     */
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

    /* ────────────────────────────────────────────────────────────────────────────────────────────
     * The size line
     * ──────────────────────────────────────────────────────────────────────────────────────────── */

    /**
     * **"0×0" IS NOT A PHOTOGRAPH AND MUST NEVER BE PRINTED.**
     *
     * The header read is off the main thread and takes a few hundred milliseconds, so "not yet known"
     * is an ordinary state for the whole of one commit — and stays the state for good on the branch
     * where this device cannot open the bytes at all.
     */
    @Test
    fun `the size line says it is still opening rather than inventing a frame`() {
        val line = dwPhotographSizeSentence(sourceWidth = 0, sourceHeight = 0, sizeBytes = 2_400_000L)
        assertFalse("a zero frame is not a frame", line.contains("0×0"))
        assertTrue(line.contains("Opening the photograph…"))
        assertTrue("the file size is known before the frame is, and is worth saying", line.contains("2.4 MB"))
    }

    /** With the header read, the line is the frame and the file — what tells two photographs apart. */
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

    /* ────────────────────────────────────────────────────────────────────────────────────────────
     * What the collapsed tracing card reports
     * ──────────────────────────────────────────────────────────────────────────────────────────── */

    /**
     * **AN UNTOUCHED CARD READS AS THE INVITATION IT WAS.**
     *
     * `dwMeasureSummary` returns null for the same state and for the same reason: a summary of nothing
     * would make a card nobody has opened look like a set-up nobody made.
     */
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

    /**
     * **IT NAMES THE PHOTOGRAPH AND THE COUNT, NEVER ONE WITHOUT THE OTHER.**
     *
     * "412 paths" does not say which sheet, on a card that can be pointed at any of the record's
     * photographs; the sheet alone does not say whether anything came out of it.
     */
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

    /**
     * **A PREVIEW SAYS SO.**
     *
     * A preview is a coarser drawing that may not be attached, and a collapsed card that reported it
     * as a trace would send a designer back expecting the thing they can actually file.
     */
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

    /**
     * **AN ATTACHMENT OUTLIVES THE DRAWING THAT MADE IT.**
     *
     * The drawing belongs to one photograph and is dropped when the photograph changes; the attached
     * file is on the record whichever photograph the card is pointed at afterwards. So the summary
     * still reports it with nothing traced.
     */
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

    /* ────────────────────────────────────────────────────────────────────────────────────────────
     * The source guards
     * ──────────────────────────────────────────────────────────────────────────────────────────── */

    private fun sourceOf(name: String): String {
        val file = File("src/main/java/com/designprototype/workshop/ui/designworkshop/$name")
        assertTrue("$name is missing — a rename must move this guard", file.exists())
        return file.readText(Charsets.UTF_8)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }

    /**
     * **A FINISHED SURFACE THAT IS REACHABLE FROM NOTHING IS THE DEFECT THIS REPOSITORY NAMES MOST.**
     *
     * `FieldRenderer.kt` records what it cost the last time: five finished, tested export surfaces
     * were reachable from no screen at all, behind a defaulted argument and a comment that read as a
     * decision. The shared-photograph section is exactly that kind of surface, so this asserts it is
     * mounted rather than merely written.
     */
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

    /**
     * **A TRACE THAT FINISHES AFTER THE PHOTOGRAPH CHANGED MUST NOT WRITE ITS RESULT.**
     *
     * This is the one bug a shared owner introduces that the panel never had: the chooser chips are
     * disabled while `running != null`, so nothing could change the photograph mid-trace — a host
     * can. Cancellation is most of the answer and not all of it, because Kotlin does not poll for
     * cancellation between two ordinary statements, and a run that has just returned from
     * `runtime.trace` is a few assignments away from `result = traced` with no suspension in between.
     *
     * A trace of sheet A shown under sheet B's name is a drawing a designer would attach believing it
     * came from what they are looking at, which is the single worst outcome this panel is written
     * against.
     */
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

    /**
     * **NEITHER DERIVATION PANEL OWNS A FILE DIALOG, AND NEITHER MAY GROW ONE.**
     *
     * Every photograph these cards can see has already been imported by `DwMediaBridge.attach`, which
     * is why they are handed a path at all. A picker inside one of them would be a second import route
     * into a feature whose whole premise is that there is exactly one — and the bytes it produced
     * would be a content Uri scoped to a task rather than a durable copy under `filesDir`.
     */
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

    /**
     * **THE PREVIEW IS NOT ALLOWED TO BE A SECOND FULL-SIZE DECODE.**
     *
     * The shared card is on screen for as long as the section is, above two cards that are already the
     * largest allocators in the app. At `DwImageDecode.DISPLAY_EDGE_PX` its thumbnail would be about
     * six megabytes held permanently; at [DW_SHARED_PREVIEW_EDGE_PX] it is under a tenth of one. This
     * is the assertion that stops somebody "simplifying" the call by dropping the argument.
     */
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
