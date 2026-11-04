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

class RecordMeasureFieldTest {

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

    @Test
    fun `a fractional reading coerces rather than being refused`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "12.4")
        assertNull(coerced.error)
        assertEquals(JsonPrimitive(12.4), coerced.value)
    }

    @Test
    fun `a whole-number reading keeps the digit its error bar earned`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "12.0")
        assertNull(coerced.error)
        assertEquals("12.0", dwRecordProposalText(coerced.value))
    }

    @Test
    fun `a reading finer than the column is rounded to what the column holds`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "4.213")
        assertNull(coerced.error)
        assertEquals("4.21", dwRecordProposalText(coerced.value))
        assertEquals(
            "Rounded to 4.21 (“Length (inches)” holds 2 decimals).",
            dwRecordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), coerced.value),
        )
    }

    @Test
    fun `a reading the column can hold is passed through and says nothing`() {
        val target = dwRecordMeasureTargets(PRODUCT_MEASURE_DIMENSIONS).first()
        val coerced = DwValues.coerce(target.field, "4.2")
        assertEquals("4.2", dwRecordProposalText(coerced.value))
        assertNull(dwRecordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), coerced.value))
    }

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

    @Test
    fun `the clamp rounds the decimal text and not its binary approximation`() {
        assertEquals("1.01", dwRecordProposalText(JsonPrimitive("1.005")))
        assertEquals(100.49999999999999, 1.005 * 100, 0.0)
    }

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

    @Test
    fun `the chooser names the grid shots`() {
        assertEquals("Grid photo 1 of 3", dwRecordPhotoLabel(0, 3, MEASUREMENT_GRID_PURPOSE))
        assertEquals("Photo 2 of 3", dwRecordPhotoLabel(1, 3, null))
        assertEquals("Photo 3 of 3", dwRecordPhotoLabel(2, 3, "SOMETHING_ELSE"))
    }

    @Test
    fun `a lone photograph is not counted against itself`() {
        assertEquals("Photo 1", dwRecordPhotoLabel(0, 1, null))
        assertEquals("Grid photo 1", dwRecordPhotoLabel(0, 1, MEASUREMENT_GRID_PURPOSE))
    }
}
