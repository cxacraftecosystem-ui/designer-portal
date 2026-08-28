package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DwPhotoMeasure
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.MEASUREMENT_GRID_PURPOSE
import com.designprototype.workshop.data.ProductCreateRequest
import com.designprototype.workshop.data.ToolCreateRequest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHERE A DETERMINISTIC MEASUREMENT MAY LAND ON A RECORD FORM, and — harder — where it may not.
 *
 * ── WHY THIS TEST AND NOT A SCREENSHOT ────────────────────────────────────────────────────────
 *
 * `DwPhotoMeasureTest` already proves the arithmetic value for value against the same constructions
 * the web's `e2e/photo-measure.spec.ts` uses, and `DwPhotoMeasureFieldTest` proves the STAGE surface
 * reaches it. This file is the third question, and it is the one `RecordMeasureField` exists to
 * answer: a record form has no registry, so every fact the registry would have declared — the type of
 * a column, its unit, its bounds — is ASSERTED in `RecordMeasureField.kt` by hand. An assertion is
 * exactly the kind of thing that is quietly wrong: nothing raises, nothing warns, the panel composes,
 * and a figure in inches lands in a column stored in something else. So the assertions are pinned.
 *
 * ── AND WHY THE REFUSALS ARE TESTED AS HARD AS THE OFFERS ─────────────────────────────────────
 *
 * `dwMeasurableLengthFields`' own header names the failure: a measurement proposed into a weight in
 * grams "is not a smaller version of the right answer", it is a plausible number in a field nobody can
 * re-check, multiplied into a cost sheet. `RecordMeasureField` routes THROUGH that function precisely
 * so record forms inherit the refusal, and [an unconvertible unit is refused] is what proves it did
 * not quietly build its targets by hand instead.
 */
class RecordMeasureFieldTest {

    /* ── The columns exist, and are the ones the form actually sends ────────────────────────── */

    /**
     * Every column named in [PRODUCT_MEASURE_DIMENSIONS] is a real property of the body the product
     * form posts. A dimension naming a column the request has no key for would propose a number into
     * a box whose value is then dropped on the floor at save — a "measurement" the designer watched
     * happen and that never reached the database.
     *
     * Java reflection rather than `kotlin-reflect`, which is not on this module's test classpath.
     */
    @Test
    fun `every product dimension names a column the product request carries`() {
        val declared = ProductCreateRequest::class.java.declaredFields.map { it.name }.toSet()
        PRODUCT_MEASURE_DIMENSIONS.forEach { dimension ->
            assertTrue(
                "ProductCreateRequest has no `${dimension.column}` — the proposal would be dropped at save",
                dimension.column in declared,
            )
        }
    }

    @Test
    fun `every tool dimension names a column the tool request carries`() {
        val declared = ToolCreateRequest::class.java.declaredFields.map { it.name }.toSet()
        TOOL_MEASURE_DIMENSIONS.forEach { dimension ->
            assertTrue(
                "ToolCreateRequest has no `${dimension.column}` — the proposal would be dropped at save",
                dimension.column in declared,
            )
        }
    }

    /**
     * THE ONE HEIGHT COLUMN THAT IS NEVER A DESTINATION.
     *
     * `ToolDocumentation` carries two heights. `heightInches` states its unit in its own name and is
     * a destination; the bare `height` beside it declares no unit anywhere, and proposing a measured
     * number into it would throw away the single fact the measurement exists to establish.
     *
     * This began life as a tripwire that failed until the handset could send `heightInches`. It could,
     * on 2026-08-27, and `TOOL_MEASURE_DIMENSIONS` gained the third column that day. What survives is
     * the half that is permanent: the unit-less column stays off the list no matter what else lands.
     */
    @Test
    fun `the tool's unitless height column is never a measurement destination`() {
        assertTrue(
            "the tool form's unitless `height` must never be a measurement destination",
            TOOL_MEASURE_DIMENSIONS.none { it.column == "height" },
        )
        assertTrue(
            "the tool's unit-bearing `heightInches` should be one, now that the handset can send it",
            TOOL_MEASURE_DIMENSIONS.any { it.column == "heightInches" },
        )
    }

    /* ── The unit is one the geometry can actually convert ──────────────────────────────────── */

    /**
     * ONE MAP DECIDES, on both surfaces. `DwPhotoMeasure.LENGTH_UNITS` is what the measurement is
     * converted through and what `dwMeasurableLengthFields` tests membership against; a unit spelled
     * any other way here would produce a destination the panel then cannot convert into, which it
     * reports on screen as "…which this cannot convert to" — a control that offers a box and then
     * refuses it.
     */
    @Test
    fun `every declared unit is one the geometry knows`() {
        (PRODUCT_MEASURE_DIMENSIONS + TOOL_MEASURE_DIMENSIONS).forEach { dimension ->
            assertTrue(
                "`${dimension.unit}` is not a unit DwPhotoMeasure can convert",
                dimension.unit in DW_RECORD_UNITS,
            )
        }
        assertTrue("inches must stay convertible", "in" in DwPhotoMeasure.LENGTH_UNITS)
    }

    /**
     * The safety property this adapter exists to inherit rather than re-implement: a unit the geometry
     * cannot convert can never become a destination. Grams is the case the stage surface's own header
     * names — a photograph cannot weigh anything.
     */
    @Test
    fun `a unit the geometry cannot convert is refused as a destination`() {
        val refused = dwRecordMeasureTargets(
            listOf(
                DwRecordDimension("weight", "Weight", unit = "g"),
                DwRecordDimension("replacementCost", "Replacement cost", unit = "INR"),
                DwRecordDimension("yearsInUse", "Years in use", unit = "years"),
            )
        )
        assertTrue(
            "no non-length column may be a measurement destination, and these got through: " +
                refused.joinToString { "${it.field.key} (${it.unit})" },
            refused.isEmpty(),
        )
    }

    /* ── The synthetic field behaves like the column it stands for ──────────────────────────── */

    @Test
    fun `the product targets are the three inch columns, in form order`() {
        val targets = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS)
        assertEquals(
            listOf("lengthInches", "breadthInches", "heightInches"),
            targets.map { it.field.key },
        )
        assertEquals(listOf("in", "in", "in"), targets.map { it.unit })
        assertEquals(
            "the button has to name the box the designer is looking at",
            listOf("Length (inches)", "Breadth (inches)", "Height (inches)"),
            targets.map { it.field.label },
        )
    }

    @Test
    fun `the tool targets are the three inch columns, in form order`() {
        val targets = dwRecordMeasureTargets(TOOL_MEASURE_DIMENSIONS)
        assertEquals(listOf("lengthInches", "breadthInches", "heightInches"), targets.map { it.field.key })
    }

    /**
     * A DECIMAL, NOT AN INT. The panel proposes through `DwValues.coerce(target.field, text)`, and an
     * INT field would take "12.4" — a perfectly ordinary reading of a small object — and answer "is
     * not a valid whole number", turning the only measurement route that works offline into a control
     * that refuses most of what it measures.
     */
    @Test
    fun `a fractional reading coerces rather than being refused`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "12.4")
        assertNull(coerced.error)
        assertEquals(JsonPrimitive(12.4), coerced.value)
    }

    /**
     * THE TRAP THIS ADAPTER EXISTS TO AVOID, PINNED.
     *
     * `MainActivity.numToText(12.0)` is "12". The panel rounds its answer to the precision its own
     * error bar reaches and says on screen that "the number of digits is the only thing left saying
     * how well it was measured", so 12.0 in ± 0.4 in and 12 in are two different claims — one about a
     * tenth of an inch and one about an inch. The proposal must reach the form's box with its digit
     * intact, which is why `dwRecordProposalText` reads the coerced value's own text and why the mount
     * assigns it directly instead of going through the form's number formatter.
     */
    @Test
    fun `a whole-number reading keeps the digit its error bar earned`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "12.0")
        assertNull(coerced.error)
        assertEquals("12.0", dwRecordProposalText(coerced.value))
    }

    /**
     * THE OTHER HALF OF THE SAME RULE, and the one the column imposes rather than the error bar.
     *
     * `roundToUncertainty` caps at four decimals and a zoomed mark routinely earns three, but every
     * dimension column is `@db.Decimal(10, 2)`. An unclamped 4.213 is accepted by the API (`ge=0`,
     * no `decimal_places`) and stored as 4.21 with nobody told, while the web port of this adapter
     * (`frontend/components/media/recordMeasure.ts`, `COLUMN_DECIMALS`) proposes 4.21 — the same
     * photograph and the same marks giving two clients two numbers.
     */
    @Test
    fun `a reading finer than the column is rounded to what the column holds`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "4.213")
        assertNull(coerced.error)
        assertEquals("4.21", dwRecordProposalText(coerced.value))
        assertEquals(
            "“Length (inches)” holds 2 decimal places, so the measured 4.213 in went in as 4.21.",
            dwRecordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), coerced.value),
        )
    }

    /** Nothing was given back, so nothing is said. A note under every proposal trains a reader past it. */
    @Test
    fun `a reading the column can hold is passed through and says nothing`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "4.2")
        assertEquals("4.2", dwRecordProposalText(coerced.value))
        assertNull(dwRecordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), coerced.value))
    }

    /**
     * A stored `0.00` in a dimension column does not read as "under five thousandths of an inch"; it
     * reads as a measurement of nothing, and it is printed that way in the report's dimensions cell.
     * A tool's needle thickness is the real case. Refused — and SAID, because a button that silently
     * did nothing is the worse of the two failures.
     */
    @Test
    fun `a reading that rounds to zero is refused out loud rather than stored as nothing`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "0.002")
        assertNull(coerced.error)
        assertEquals("", dwRecordProposalText(coerced.value))
        val note = dwRecordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), coerced.value)
        assertNotNull(note)
        assertTrue("it has to name the box:\n$note", note!!.contains("Length (inches)"))
        assertTrue("and say that nothing was written:\n$note", note.contains("nothing was put in it"))
    }

    /**
     * DECIMAL TEXT, NOT BINARY FLOATING POINT. The figure on the button is the promise, so the clamp
     * has to round the number that was PRINTED rather than its binary approximation.
     */
    @Test
    fun `the clamp rounds the decimal text and not its binary approximation`() {
        assertEquals("1.01", dwRecordProposalText(JsonPrimitive("1.005")))
        // The scale-by-100 route, shown rather than argued about: this is the answer the clamp must
        // NOT give, and it is why `BigDecimal` is not a stylistic choice here.
        assertEquals(100.49999999999999, 1.005 * 100, 0.0)
    }

    /**
     * The server declares `ge=0` on every one of these columns. A distance cannot be negative, so this
     * can only fire on a bug — and when it does it fires here, naming the box, rather than as a 422
     * after the designer has walked away from the loom.
     */
    @Test
    fun `a negative reading is refused on this screen and names the box`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "-3")
        assertNull(coerced.value)
        assertNotNull(coerced.error)
        assertTrue(
            "the refusal has to say which box it is about",
            coerced.error!!.contains("Length (inches)"),
        )
    }

    /* ── What the panel is told the boxes already hold ──────────────────────────────────────── */

    /**
     * The "Currently “14”. This replaces it." warning is the only thing standing between a proposal
     * and the silent overwrite of a number somebody measured with callipers. It is driven off this
     * map, and a blank box must not produce an entry — `DwValues.text` cannot tell an absent key from
     * a blank primitive, so an entry carrying no information is one more thing that can go wrong.
     */
    @Test
    fun `only the boxes that hold something are reported to the panel`() {
        val values = dwRecordRowValues(
            mapOf(
                "lengthInches" to " 14 ",
                "breadthInches" to "",
                "heightInches" to "   ",
            )
        )
        assertEquals(setOf("lengthInches"), values.keys)
        assertEquals("14", DwValues.text(values["lengthInches"]))
    }

    /* ── Telling the photographs apart ──────────────────────────────────────────────────────── */

    /**
     * The grid shots are the ones most likely to have a reference in them — that is what a grid sheet
     * is — so they are named in the chooser rather than being one more "Photo 3".
     */
    @Test
    fun `the chooser names the grid shots`() {
        assertEquals("Grid photo 1 of 3", dwRecordPhotoLabel(0, 3, MEASUREMENT_GRID_PURPOSE))
        assertEquals("Photo 2 of 3", dwRecordPhotoLabel(1, 3, null))
        assertEquals("Photo 3 of 3", dwRecordPhotoLabel(2, 3, "SOMETHING_ELSE"))
    }

    /** One photograph gets no "of 1": the chooser is not even drawn, and the count would be noise. */
    @Test
    fun `a lone photograph is not counted against itself`() {
        assertEquals("Photo 1", dwRecordPhotoLabel(0, 1, null))
        assertEquals("Grid photo 1", dwRecordPhotoLabel(0, 1, MEASUREMENT_GRID_PURPOSE))
    }
}
