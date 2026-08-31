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

/**
 * TENTATIVE-FIRST, PINNED — the ordering rule the owner asked for, and the two properties of it that
 * nothing on a screen can show.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS TEST EXISTS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * "Bring them to the top of the list" is one sentence with two silent failures behind it. The first
 * is that a designer's own arrangement INSIDE each group is thrown away — a rule that SORTED rather
 * than partitioned would look identical on a workshop with three sketches and would scramble one
 * with nine. The second is worse and is the reason `ordinal` is never written: unticking the box has
 * to put the row back exactly where it was, and a screenshot of a correct list and a screenshot of a
 * list that can never be undone are the same screenshot.
 *
 * Both are questions about which list a function returns, which is what a JVM test can hold still on
 * a laptop where a courtyard cannot be reproduced. `frontend/e2e/sketch-tentative-unit.spec.ts` pins
 * the identical cases on the web, so the two clients cannot come to disagree about which sketch is
 * at the top of one workshop's list.
 *
 * IT ALSO PINS THE SELECTION FALLBACK, which is the one place the two halves could drift apart on
 * this client alone: the picker draws its options through [dwTentativeFirst] and
 * [dwChooserKeepSelection] answers "which row is chosen when the old one has gone". If those
 * disagree the control shows one row at the top and holds another as its value, and the capture
 * cards write to the value — a photograph filed against a sketch nobody chose, silently.
 */
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
        // The owner's second clause: "the ones for which it is not checked would be considered as
        // normal as they are treated right now". On almost every workshop this is the whole
        // behaviour, so it is the case worth failing loudly on.
        assertEquals(listOf("a", "b", "c"), ordered(listOf(row("a"), row("b", false), row("c"))))
    }

    @Test
    fun `a list with everything ticked is returned in exactly the order it arrived`() {
        val rows = listOf(row("a", true), row("b", true), row("c", true))
        assertEquals(listOf("a", "b", "c"), ordered(rows))
    }

    @Test
    fun `unticking restores the row to precisely the position it would have had`() {
        /*
          THE PROPERTY THE WHOLE DESIGN TURNS ON. `ordinal` is never written, so the stored list is
          untouched by a tick; partitioning it again with the box cleared must therefore reproduce the
          stored order exactly. A version that wrote the ordinal would pass the first assertion here
          and fail this one, and on a real record that failure is permanent.
        */
        val stored = listOf(row("a"), row("b"), row("c"), row("d"))
        val ticked = stored.dropLast(1) + row("d", tentative = true)
        assertEquals(listOf("d", "a", "b", "c"), ordered(ticked))

        val unticked = stored.dropLast(1) + row("d", tentative = false)
        assertEquals(names(stored), ordered(unticked))
    }

    @Test
    fun `the partition is generic so a caller can keep each row's original stage position`() {
        // The picker's shape: the label falls back to "Untitled 3" and the hint reads "Row 3 of 8",
        // and both of those numbers are the position on the STAGE FORM.
        val rows = listOf(row("a"), row("b", tentative = true), row("c"))
        val pairs = rows.mapIndexed { index, r -> r to index }
        val ordered = dwTentativeFirst(pairs) { (r, _) -> dwIsTentativeRow(r.values) }
        assertEquals(listOf(1, 0, 2), ordered.map { it.second })
        // AND THE NUMBER TRAVELS WITH THE ROW, WHICH IS THE POINT. The tentative row is drawn FIRST
        // and is still the SECOND row of the stage, so an unnamed one reads "Untitled 2" here and
        // "Untitled 2" on the stage form. A partition over bare rows would have renamed it to 1.
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
        // `JsonNull` IS a `JsonPrimitive`, and reading `.content` off one hands back the string
        // "null" — the trap `dwChooserRowLabel` carries its own note about. `DwValues.bool` is what
        // keeps this arm honest, which is why the predicate goes through it rather than casting.
        assertFalse(dwIsTentativeRow(mapOf(DW_TENTATIVE_FIELD_KEY to JsonNull)))
    }

    @Test
    fun `the field is found by its registry declaration and absence is an ordinary state`() {
        val declared = FieldDto(key = DW_TENTATIVE_FIELD_KEY, label = "Tentative", type = "BOOL")
        fun entity(vararg fields: FieldDto) =
            EntityDto(key = "sketch", cardinality = "COLLECTION", fields = fields.toList())

        assertEquals("Tentative", dwTentativeField(entity(declared))?.label)
        // An older build talking to a registry without the field draws no word rather than throwing.
        assertNull(dwTentativeField(entity()))
        assertNull(dwTentativeField(null))
        // A deprecated declaration is a dead input everywhere else in this client and is dead here.
        assertNull(dwTentativeField(entity(declared.copy(deprecated = true))))
        // The key alone is not enough: a same-named field of another type is not this flag.
        assertNull(dwTentativeField(entity(declared.copy(type = "TEXT"))))
    }

    @Test
    fun `the selection fallback lands on the row the picker draws at the top`() {
        val rows = listOf(row("a"), row("b", tentative = true), row("c"))
        // Nothing chosen yet, and a chosen row that has since gone, both fall back — and the fallback
        // is the FIRST OFFERED row, not the first stored one. See `dwChooserKeepSelection`.
        assertEquals(dwChooserRowKey(rows[1]), dwChooserKeepSelection("", rows))
        assertEquals(dwChooserRowKey(rows[1]), dwChooserKeepSelection("sketch:gone", rows))
        // A selection that is still in the list is never moved, whatever the flags say.
        assertEquals(dwChooserRowKey(rows[2]), dwChooserKeepSelection(dwChooserRowKey(rows[2]), rows))
        // An empty collection still answers with a blank rather than throwing on `first()`.
        assertEquals("", dwChooserKeepSelection("", emptyList()))
    }
}
