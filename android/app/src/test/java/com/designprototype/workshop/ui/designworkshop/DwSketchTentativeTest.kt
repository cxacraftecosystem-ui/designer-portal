package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.dwRowId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwSketchTentativeTest {

    private fun row(name: String, tentative: Boolean? = null): DraftRow {
        val values = buildMap {
            put("name", JsonPrimitive(name))
            if (tentative != null) put(DW_TENTATIVE_FIELD_KEY, JsonPrimitive(tentative))
        }
        return DraftRow(id = dwRowId(DW_CHOOSER_SKETCH_ENTITY, name), values = values)
    }

    private fun names(rows: List<DraftRow>): List<String> =
        rows.map { (it.values["name"] as JsonPrimitive).content }

    private fun ordered(rows: List<DraftRow>): List<String> =
        names(dwTentativeFirst(rows) { dwIsTentativeRow(it.values) })

    @Test
    fun `tentative rows come first and each group keeps its own stored order`() {
        val rows = listOf(
            row("a"),
            row("b", tentative = true),
            row("c"),
            row("d", tentative = true),
            row("e", tentative = false),
            row("f", tentative = true),
        )
        assertEquals(listOf("b", "d", "f", "a", "c", "e"), ordered(rows))
    }

    @Test
    fun `a list with nothing ticked is returned in exactly the order it arrived`() {
        assertEquals(listOf("a", "b", "c"), ordered(listOf(row("a"), row("b", false), row("c"))))
    }

    @Test
    fun `a list with everything ticked is returned in exactly the order it arrived`() {
        val rows = listOf(row("a", true), row("b", true), row("c", true))
        assertEquals(listOf("a", "b", "c"), ordered(rows))
    }

    @Test
    fun `unticking restores the row to precisely the position it would have had`() {
        val stored = listOf(row("a"), row("b"), row("c"), row("d"))
        val ticked = stored.dropLast(1) + row("d", tentative = true)
        assertEquals(listOf("d", "a", "b", "c"), ordered(ticked))

        val unticked = stored.dropLast(1) + row("d", tentative = false)
        assertEquals(names(stored), ordered(unticked))
    }

    @Test
    fun `the partition is generic so a caller can keep each row's original stage position`() {
        val rows = listOf(row("a"), row("b", tentative = true), row("c"))
        val pairs = rows.mapIndexed { index, r -> r to index }
        val ordered = dwTentativeFirst(pairs) { (r, _) -> dwIsTentativeRow(r.values) }
        assertEquals(listOf(1, 0, 2), ordered.map { it.second })
        assertEquals(
            "Untitled 2",
            dwChooserRowLabel(DraftRow(id = "sketch:x"), ordered.first().second),
        )
    }

    @Test
    fun `the predicate reads a boolean and treats not answered as not tentative`() {
        assertTrue(dwIsTentativeRow(row("a", tentative = true).values))
        assertFalse(dwIsTentativeRow(row("a", tentative = false).values))
        assertFalse(dwIsTentativeRow(row("a").values))
        assertFalse(dwIsTentativeRow(null))
        assertFalse(dwIsTentativeRow(mapOf(DW_TENTATIVE_FIELD_KEY to JsonNull)))
    }

    @Test
    fun `the field is found by its registry declaration and absence is an ordinary state`() {
        val declared = FieldDto(key = DW_TENTATIVE_FIELD_KEY, label = "Tentative", type = "BOOL")
        fun entity(vararg fields: FieldDto) =
            EntityDto(key = "sketch", cardinality = "COLLECTION", fields = fields.toList())

        assertEquals("Tentative", dwTentativeField(entity(declared))?.label)
        assertNull(dwTentativeField(entity()))
        assertNull(dwTentativeField(null))
        assertNull(dwTentativeField(entity(declared.copy(deprecated = true))))
        assertNull(dwTentativeField(entity(declared.copy(type = "TEXT"))))
    }

    @Test
    fun `the selection fallback lands on the row the picker draws at the top`() {
        val rows = listOf(row("a"), row("b", tentative = true), row("c"))
        assertEquals(dwChooserRowKey(rows[1]), dwChooserKeepSelection("", rows))
        assertEquals(dwChooserRowKey(rows[1]), dwChooserKeepSelection("sketch:gone", rows))
        assertEquals(dwChooserRowKey(rows[2]), dwChooserKeepSelection(dwChooserRowKey(rows[2]), rows))
        assertEquals("", dwChooserKeepSelection("", emptyList()))
    }
}
