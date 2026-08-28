package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwPhotoMeasure
import com.designprototype.workshop.data.DwRoundedValue
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
 * What "measure a dimension from a photograph" is OFFERED ON, pinned against the bundled registry.
 *
 * ── WHY THIS TEST AND NOT A SCREENSHOT ────────────────────────────────────────────────────────
 *
 * `DwPhotoMeasureTest` already proves the arithmetic, value for value, against the same constructions
 * the web's `e2e/photo-measure.spec.ts` uses. What it cannot prove — and what this repository has now
 * got wrong five times — is that anything REACHES it. The panel is reachable only where
 * [dwOffersPhotoMeasure] returns true, and that function is a pair of declaration lookups: a wrong
 * type token or a unit spelled differently in the registry does not raise, does not warn and does not
 * log. It returns false, the panel is not composed, and the feature is invisible on exactly the
 * screens it was written for — which reads, to the designer holding the phone, as a feature that does
 * not exist.
 *
 * IT PINS THE SHIPPED ASSET AND NOT THE LIVE SERVER, for the same reason `DwFindingsSurfaceTest`
 * does: `design-workshop-schema.json` is what a handset renders from before it has ever had a
 * connection, so it is the copy that decides what a courtyard sees.
 *
 * ── AND WHY IT ALSO CHECKS WHAT IS *NOT* OFFERED ──────────────────────────────────────────────
 *
 * A measurement proposed into a weight in grams, or into a price, is not a smaller version of the
 * right answer. It is a plausible number in a field nobody can re-check, multiplied into a cost sheet
 * — the exact failure the whole feature exists to reduce. So the refusals are tested as hard as the
 * offers.
 *
 * ── AND, SINCE 2026-08-28, WHETHER ANYBODY CAN GET BACK OUT OF IT ─────────────────────────────
 *
 * The second half of this class is the accordion (reqs 4.2 and 4.3). It is the same failure in a
 * different place: a panel nobody can reach and a panel nobody can put away both end with a designer
 * not using it. Those tests read the SOURCE rather than a composition, and the block comment above
 * them says why that is the only shape available on this module's test classpath.
 */
class DwPhotoMeasureFieldTest {

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private fun entityOf(stageKey: String, entityKey: String): EntityDto =
        schema.stages.firstOrNull { it.key == stageKey }?.entity(entityKey)
            ?: throw AssertionError("the registry declares no `$stageKey`.`$entityKey`")

    private fun siblingsOf(stageKey: String, entityKey: String): Map<String, FieldDto> =
        entityOf(stageKey, entityKey).liveFields.associateBy { it.key }

    /**
     * The four entities the offer is meant to appear on, and the dimensions it proposes into.
     *
     * These are exactly the registry's entities that describe a physical object somebody photographs.
     * A rename of `lengthCm` or a change of its declared unit takes the offer away silently, so the
     * keys are named here rather than counted.
     */
    @Test
    fun `every entity that describes a photographed object offers the measurement`() {
        val expected = mapOf(
            ("EXISTING_PRODUCTS_BASELINE" to "existingProduct") to listOf("lengthCm", "widthCm", "heightCm"),
            ("SKETCH_DEVELOPMENT" to "sketch") to listOf("lengthCm", "widthCm", "heightCm"),
            ("PROTOTYPE_DEVELOPMENT" to "prototype") to
                listOf("lengthCm", "widthCm", "heightCm", "diameterCm"),
            ("FINAL_PROTOTYPE_DOCUMENTATION" to "finalProduct") to listOf("lengthCm", "widthCm", "heightCm"),
        )
        for ((location, keys) in expected) {
            val (stageKey, entityKey) = location
            val siblings = siblingsOf(stageKey, entityKey)
            assertEquals(
                "`$stageKey`.`$entityKey` no longer proposes into the dimensions the registry declares",
                keys,
                dwMeasurableLengthFields(siblings).map { it.field.key },
            )
        }
    }

    /**
     * The offer is made on EVERY image field of a qualifying entity, and on none of its other fields.
     *
     * Stage 13's prototype declares two — `prototypePhotos` and `turntablePhotos` — and both get it,
     * because nothing in this app can tell which photograph the designer laid a ruler beside. That is
     * the whole argument in [dwOffersPhotoMeasure], and it is the part a "tidy-up" would undo.
     */
    @Test
    fun `the offer lands on the image fields and nowhere else`() {
        val siblings = siblingsOf("PROTOTYPE_DEVELOPMENT", "prototype")
        val offered = siblings.values.filter { dwOffersPhotoMeasure(it, siblings) }.map { it.key }
        assertEquals(listOf("prototypePhotos", "turntablePhotos"), offered)

        // The dimension fields themselves must not offer it: a panel under `lengthCm` would be a
        // measuring surface with no photograph on it.
        assertFalse(dwOffersPhotoMeasure(siblings.getValue("lengthCm"), siblings))
    }

    /** An entity with photographs but no length field gets no offer — there is nowhere to propose into. */
    @Test
    fun `photographs alone are not enough`() {
        val siblings = mapOf(
            "photos" to FieldDto(key = "photos", label = "Photographs", type = "IMAGE_LIST"),
            "note" to FieldDto(key = "note", label = "Note", type = "LONG_TEXT"),
        )
        assertTrue(dwMeasurableLengthFields(siblings).isEmpty())
        assertFalse(dwOffersPhotoMeasure(siblings.getValue("photos"), siblings))
    }

    /**
     * Everything a photograph cannot measure is refused as a destination.
     *
     * A photograph cannot weigh anything, cannot count days and cannot price a pot. Each of these
     * would have accepted a centimetre figure and printed it, in a field that is multiplied into a
     * cost sheet by somebody who was not in the room.
     */
    @Test
    fun `weights prices counts and unknown units are never destinations`() {
        val siblings = listOf(
            FieldDto(key = "weightG", label = "Weight", type = "DECIMAL", unit = "g"),
            FieldDto(key = "durationDays", label = "Duration", type = "INT", unit = "days"),
            FieldDto(key = "price", label = "Price", type = "MONEY", unit = "cm"),
            FieldDto(key = "share", label = "Share", type = "PERCENT", unit = "cm"),
            FieldDto(key = "span", label = "Span", type = "DECIMAL", unit = "hands"),
            FieldDto(key = "retired", label = "Old length", type = "DECIMAL", unit = "cm", deprecated = true),
            FieldDto(key = "caption", label = "Caption", type = "TEXT", unit = "cm"),
        ).associateBy { it.key }
        assertEquals(emptyList<String>(), dwMeasurableLengthFields(siblings).map { it.field.key })
    }

    /** A declared unit in another case, or with a stray space, is still a length. */
    @Test
    fun `the declared unit is read case-folded and trimmed`() {
        val siblings = listOf(
            FieldDto(key = "a", label = "A", type = "DECIMAL", unit = " CM "),
            FieldDto(key = "b", label = "B", type = "INT", unit = "MM"),
        ).associateBy { it.key }
        assertEquals(listOf("cm", "mm"), dwMeasurableLengthFields(siblings).map { it.unit })
    }

    /**
     * ONE MAP. Every unit this offers as a destination must be one [DwPhotoMeasure] can convert into.
     *
     * The two are wired to the same map on purpose, so this can only fail if somebody introduces a
     * second opinion about what a length is — which is how a field would come to be offered as a
     * destination that the propose button then refuses to convert into, leaving a designer looking at
     * a control that does nothing.
     */
    @Test
    fun `every offered destination is a unit the module can convert`() {
        val entities = listOf(
            "EXISTING_PRODUCTS_BASELINE" to "existingProduct",
            "SKETCH_DEVELOPMENT" to "sketch",
            "PROTOTYPE_DEVELOPMENT" to "prototype",
            "FINAL_PROTOTYPE_DOCUMENTATION" to "finalProduct",
        )
        for ((stageKey, entityKey) in entities) {
            for (target in dwMeasurableLengthFields(siblingsOf(stageKey, entityKey))) {
                assertTrue(
                    "`${target.field.key}` is offered in ${target.unit}, which the module cannot convert",
                    DwPhotoMeasure.LENGTH_UNITS.containsKey(target.unit)
                )
                assertEquals(
                    1.0,
                    DwPhotoMeasure.convertLength(1.0, target.unit, target.unit) ?: 0.0,
                    0.0,
                )
            }
        }
    }

    /**
     * The proposal is rendered at exactly the precision its error bar reached, and NOT one digit more.
     *
     * The rounding happens once, in [DwPhotoMeasure.roundToUncertainty], through its port of
     * JavaScript's `Math.round`; [dwFormatRounded] only writes the digits out. A formatter that did
     * its own rounding would put the handset and the browser one unit apart on every exact binary tie
     * — which is the divergence that module's header is entirely about.
     */
    @Test
    fun `a proposal is written to the decimals its error bar supports`() {
        assertEquals("20.0", dwFormatRounded(DwRoundedValue(20.0, 1)))
        assertEquals("12", dwFormatRounded(DwRoundedValue(12.0, 0)))
        assertEquals("0.0335", dwFormatRounded(DwRoundedValue(0.0335, 4)))
        // What the module actually produces for a 19.98471 cm reading with a 0.3 cm bar: one decimal.
        val rounded = DwPhotoMeasure.roundToUncertainty(19.98471, 0.3)
        assertEquals(1, rounded.decimals)
        assertEquals("20.0", dwFormatRounded(rounded))
    }

    /* ────────────────────────────────────────────────────────────────────────────────────────────
     * THE ACCORDION — reqs 4.2 and 4.3, reported 2026-08-28
     *
     * The report was two sentences. "Once it is expanded there is no way to minimise it again after
     * configuration", and "the configured state should not force the user to repeatedly scroll
     * through the expanded card." The card DID have a Close button; it sat at the top of a card that
     * is a photograph, a nudge pad, two text fields and a readout tall, and pressing it threw away
     * every mark — so the honest reading is that the only exit was both far away and expensive, and
     * a designer therefore scrolled instead. Three things had to change together, and these tests
     * pin all three: the summary that makes a collapsed card readable, the marks that survive the
     * collapse, and the second door at the foot.
     * ──────────────────────────────────────────────────────────────────────────────────────────── */

    /**
     * The collapsed card says what is set up, so nobody has to expand it to find out.
     *
     * This is the half of the report that is about scrolling. A collapsed card that shows only its
     * own title is indistinguishable from one nobody has touched.
     */
    @Test
    fun `the collapsed summary names the marks the reference and the method`() {
        assertEquals(
            "4 of 4 marks placed · 120 mm reference · same-plane method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "120",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
    }

    /**
     * THE TOTAL IS NAMED, NEVER JUST THE COUNT — the single most repeated bug class in this
     * repository is a number that quietly stops. The same-plane method needs four marks and the
     * four-corner method needs six, so a bare "4 marks placed" reads as finished under one and
     * half-done under the other, on a card whose whole subject is how sure a measurement is.
     */
    @Test
    fun `the four corner method is counted out of six and names its rectangle`() {
        assertEquals(
            "4 of 6 marks placed · 210 × 297 mm rectangle · four-corner method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 6,
                fourCorner = true,
                referenceLength = "",
                referenceUnit = "mm",
                rectWidth = "210",
                rectHeight = "297",
                rectUnit = "mm",
                photographName = null,
            )
        )
    }

    /**
     * A card nobody has touched has no summary, and gets the invitation prose instead.
     *
     * Null and not an empty string: the two collapsed states say different things, and a blank line
     * where a sentence belongs is the failure mode this file's header is about.
     */
    @Test
    fun `an untouched card is not described as a configuration`() {
        assertNull(
            dwMeasureSummary(
                marksPlaced = 0,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
        // Whitespace is not a reference length. A designer who typed a space into the box and closed
        // the card has configured nothing, and must not be told they have.
        assertNull(
            dwMeasureSummary(
                marksPlaced = 0,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "   ",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
    }

    /**
     * What is MISSING is stated outright rather than left out of the sentence.
     *
     * An omitted clause and a satisfied one look identical, so a summary that simply dropped the
     * reference when there was none would read, at a glance, exactly like one that had it.
     */
    @Test
    fun `a half configured card says which half is still missing`() {
        assertEquals(
            "4 of 4 marks placed · no reference length yet · same-plane method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
        assertEquals(
            "no marks placed yet · 300 mm reference · same-plane method",
            dwMeasureSummary(
                marksPlaced = 0,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "300",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
        assertEquals(
            "2 of 6 marks placed · no rectangle size yet · four-corner method",
            dwMeasureSummary(
                marksPlaced = 2,
                marksNeeded = 6,
                fourCorner = true,
                referenceLength = "",
                referenceUnit = "mm",
                rectWidth = "210",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
    }

    /**
     * The chosen photograph is part of what a collapse preserves, so it is part of what the summary
     * reports — but only where the field holds more than one and there was a choice to make.
     *
     * NOT TRUNCATED. A summary that abbreviated the filename would be this file's own worst bug
     * class, a line that quietly stops, committed in miniature on the one line whose entire job is
     * to say what is already set up.
     */
    @Test
    fun `the chosen photograph is named only where there was a choice`() {
        assertEquals(
            "4 of 4 marks placed · 120 mm reference · same-plane method · photograph “IMG_0042.jpg”",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "120",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = "IMG_0042.jpg",
            )
        )
        // One photograph is not a decision anybody made, so there is no clause about it.
        assertEquals(
            "4 of 4 marks placed · 120 mm reference · same-plane method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "120",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
        // A blank name is treated as no name rather than printed as an empty pair of quotes.
        assertEquals(
            "4 of 4 marks placed · 120 mm reference · same-plane method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "120",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = "   ",
            )
        )
    }

    /* ────────────────────────────────────────────────────────────────────────────────────────────
     * The three source guards below
     *
     * WHY THE SOURCE AND NOT A COMPOSITION. There is no Robolectric and no `createComposeRule` on
     * this module's unit-test classpath — `app/build.gradle.kts` declares exactly one test
     * dependency, `junit:junit:4.13.2` — so a composed assertion cannot run here at all. Re-check
     * with, on 2026-08-28:
     *
     *     grep -n "testImplementation" android/app/build.gradle.kts
     *
     * The same shape is already used by `DwAiVerbSurfaceGuardTest`, `DwDocumentOpenTargetTest` and
     * `DwReportPaginationTest`, and its limits are the same: it reads text, so somebody determined to
     * undo one of these can rename a helper and slip past. The point is not to make the regression
     * impossible but to make it DELIBERATE, past a test that names the report it came from.
     * ──────────────────────────────────────────────────────────────────────────────────────────── */

    /** The unit tests run with `app/` as the working directory — see `DwSketchRectifyFieldTest`. */
    private fun panelSource(): String {
        val file = File(
            "src/main/java/com/designprototype/workshop/ui/designworkshop/DwPhotoMeasureField.kt"
        )
        assertTrue("DwPhotoMeasureField.kt is missing — a rename must move this guard", file.exists())
        return file.readText(Charsets.UTF_8)
    }

    /**
     * The same source with its comments removed.
     *
     * NECESSARY RATHER THAN TIDY: this file argues about the very literals these tests forbid — the
     * `.height(300.dp)` that reserved the empty space is quoted, in prose, in the comment that
     * explains why it is gone. A guard that could not tell a mention from a call would fail on the
     * explanation of its own rule. Comments in this file do not nest (there is no `/` `*` inside a
     * block comment) and it contains no `//` inside a string literal, so a single non-greedy pass
     * over each form is enough.
     */
    private fun panelCode(): String = panelSource()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    /**
     * **COLLAPSING KEEPS THE MARKS.** The substance of "the configured state should not force the
     * user to repeatedly scroll".
     *
     * Collapsing removes `DwPhotoMeasureOpen` from the composition, so anything `remember`ed inside
     * it is destroyed. Until 2026-08-28 that was ALL of it — the marks, the reference length, the
     * method and the chosen photograph — and the decode effect cleared the marks unconditionally on
     * top, so re-expanding presented an untouched card. That made the one exit expensive, which is
     * why nobody used it and why the report is about scrolling rather than about a missing button.
     *
     * Three properties keep it fixed, and each is asserted here: the configuration is held by the
     * PANEL, the open half `remember`s none of it, and the only `marks.clear()` left in the file is
     * the one guarded by a change of photograph.
     */
    @Test
    fun `collapsing and reopening cannot clear the marks`() {
        val code = panelCode()

        assertTrue(
            "the configuration must be held by DwPhotoMeasurePanel, which stays composed across a " +
                "collapse — a holder created inside the open half is destroyed by the collapse it " +
                "is supposed to survive",
            code.contains("val config = remember { DwMeasureConfig(")
        )
        assertTrue(
            "the open half must be handed the configuration rather than owning it",
            code.contains("config: DwMeasureConfig")
        )

        // Nothing a designer typed or placed may be remembered inside the open half again.
        listOf(
            "val marks = remember",
            "var active by remember",
            "var mode by remember",
            "var photoId by remember",
            "var referenceLength by remember",
            "var referenceUnit by remember",
            "var rectWidth by remember",
            "var rectHeight by remember",
            "var rectUnit by remember",
        ).forEach { token ->
            assertFalse(
                "`$token` is back inside DwPhotoMeasureOpen. The open half is removed from the " +
                    "composition by a collapse, so anything remembered there is thrown away with it " +
                    "— and a collapse is only cheap if nothing is lost. See DwMeasureConfig.",
                code.contains(token)
            )
        }

        // ONE clear in the whole file, and it is the guarded one.
        assertEquals(
            "there must be exactly one `marks.clear()` in this file, inside DwMeasureConfig, guarded " +
                "by a change of photograph. A second one — in particular back inside the decode " +
                "LaunchedEffect, which re-runs on every expansion — wipes the designer's work every " +
                "time they reopen the card.",
            1,
            code.split("marks.clear()").size - 1,
        )
        val guard = code.substringAfter("fun usePhotograph(").substringBefore("marks.clear()")
        assertTrue(
            "the surviving `marks.clear()` must sit behind the `marksPhotoId` comparison — that " +
                "comparison is the only thing distinguishing a change of photograph (which must " +
                "clear) from a re-entry into the composition (which must not)",
            guard.contains("if (id == marksPhotoId) return")
        )
        val decode = code.substringAfter("LaunchedEffect(photo.id) {").substringBefore("image = null")
        assertTrue(
            "the decode effect must route the photograph through DwMeasureConfig.usePhotograph, " +
                "which is where the guard lives",
            decode.contains("config.usePhotograph(photo.id)")
        )
    }

    /**
     * **TWO WAYS OUT, AND THE SECOND ONE IS WHERE THE WORK ENDS.**
     *
     * The header's door has always existed; it is a whole card away by the time a designer has
     * placed six marks, typed the reference and read the answer, which is what the report describes
     * scrolling back up from. So there is a second, and it is placed after the propose buttons —
     * asserted here by position, because a collapse control that drifted above the readout would
     * leave the bottom of the card exactly as it was.
     *
     * Both are the same composable saying the same constant, so the two doors cannot come to be
     * labelled differently.
     */
    @Test
    fun `the expanded card has a collapse control at the head and at the foot`() {
        val code = panelCode()

        assertTrue(
            "the header's collapse control is missing — the existing door must be kept, not replaced",
            code.contains("DwMeasureCollapseButton(prominent = false")
        )
        assertTrue(
            "the foot's collapse control is missing — without it the only way out is back at the top",
            code.contains("DwMeasureCollapseButton(prominent = true")
        )
        assertTrue(
            "both doors must say DW_MEASURE_COLLAPSE_WORD, so neither can be relabelled alone",
            code.contains("Text(DW_MEASURE_COLLAPSE_WORD")
        )
        assertEquals(
            "the collapse control must be ONE composable used twice; a second definition is a second " +
                "place for the wording and the 48dp floor to drift",
            1,
            code.split("private fun DwMeasureCollapseButton(").size - 1,
        )

        val readout = code.indexOf("DwMeasurementReadout(")
        val foot = code.indexOf("DwMeasureCollapseButton(prominent = true")
        assertTrue("DwMeasurementReadout is no longer composed by the open card", readout > 0)
        assertTrue(
            "the foot's collapse control must come AFTER the readout and its propose buttons — that " +
                "is the point at which the designer is finished, and it is the point the report says " +
                "they were scrolling away from",
            foot > readout
        )
    }

    /**
     * **THE HEADER ROW IS THE ACCORDION CONTROL, AND IT ANNOUNCES ITSELF.**
     *
     * `stateDescription` is what makes TalkBack say "Expanded" / "Collapsed" — `selectable` would
     * say "selected", which is the wrong noun for a thing that opens and closes — `onClickLabel` is
     * what says what the press will DO, and [androidx.compose.ui.semantics.Role.Button] says what
     * kind of thing it is. A rotating chevron carries none of the three.
     *
     * The last assertion is the spacing half of the report: the viewport reserved a flat 300dp
     * whether or not there was a photograph in it yet, and letterboxed every landscape frame inside
     * that shape once there was. Both of those are the same literal, and it must not come back.
     */
    @Test
    fun `the header announces expanded and collapsed and the viewport reserves nothing`() {
        val code = panelCode()

        assertTrue(
            "the header row must be clickable with a label saying what the press does",
            code.contains("onClickLabel = if (expanded)")
        )
        assertTrue(
            "the header row must carry Role.Button — a tappable row is not announced as a control",
            code.contains("role = Role.Button")
        )
        assertTrue(
            "the header row must expose its own state, or a reader who cannot see the chevron has " +
                "no way to know whether the card is open",
            code.contains("stateDescription = if (expanded) \"Expanded\" else \"Collapsed\"")
        )
        assertTrue(
            "the chevron must still turn, and must still collapse to a snap under reducedMotion",
            code.contains(".rotate(turn)") && code.contains("if (reduceMotion) snap()")
        )

        assertFalse(
            "the photograph's viewport is a fixed 300dp again. That literal is the empty space in " +
                "the report, twice over: it reserved a third of the screen before the decode landed, " +
                "and it letterboxed every landscape photograph inside a shape that was not its own.",
            code.contains(".height(300.dp)")
        )
        assertTrue(
            "the viewport must take the working copy's own aspect ratio once there is one",
            code.contains("Modifier.aspectRatio(viewportRatio)")
        )
        assertTrue(
            "and must wrap its contents, over a floor, while there is nothing to show",
            code.contains("Modifier.heightIn(min = EMPTY_VIEWPORT_MIN_HEIGHT)")
        )
    }
}
